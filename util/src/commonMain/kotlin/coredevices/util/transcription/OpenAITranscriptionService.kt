package coredevices.util.transcription

import coredevices.resampler.Resampler
import coredevices.util.AudioEncoding
import coredevices.util.CoreConfigFlow
import coredevices.util.OpenAITranscriptionConfig
import coredevices.util.integrations.IntegrationTokenStorage
import coredevices.util.writeWavHeader
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.io.IOException
import kotlin.time.Duration

const val OPENAI_TRANSCRIPTION_API_KEY_STORAGE_KEY = "openai_transcription_api_key"

private const val TARGET_SAMPLE_RATE = 16_000
private const val MAX_PROMPT_CHARACTERS = 4_000
private const val MAX_CONVERSATION_MESSAGES = 8

/** Returns the full OpenAI-compatible transcription endpoint for a valid HTTPS configuration. */
fun OpenAITranscriptionConfig.transcriptionUrlOrNull(): String? {
    val configuredEndpoint = endpoint.trim()
    if (configuredEndpoint.isBlank() || model.isBlank()) return null
    val configuredUrl = runCatching { Url(configuredEndpoint) }.getOrNull() ?: return null
    if (
        configuredUrl.protocol.name != "https" ||
        configuredUrl.host.isBlank() ||
        configuredUrl.user != null ||
        configuredUrl.password != null ||
        !configuredUrl.parameters.isEmpty() ||
        configuredUrl.trailingQuery ||
        configuredUrl.fragment.isNotEmpty() ||
        '?' in configuredEndpoint ||
        '#' in configuredEndpoint
    ) return null

    val base = configuredEndpoint.trimEnd('/')
    val fullUrl = when {
        base.endsWith("/audio/transcriptions") -> base
        base.endsWith("/v1") -> "$base/audio/transcriptions"
        else -> "$base/v1/audio/transcriptions"
    }
    val url = runCatching { Url(fullUrl) }.getOrNull() ?: return null
    return fullUrl.takeIf {
        url.protocol.name == "https" &&
            url.host.isNotBlank() &&
            url.user == null &&
            url.password == null &&
            url.parameters.isEmpty() &&
            !url.trailingQuery &&
            url.fragment.isEmpty()
    }
}

fun OpenAITranscriptionConfig.isConfigured(): Boolean = transcriptionUrlOrNull() != null

internal fun buildOpenAIPrompt(
    configuredPrompt: String,
    contentContext: String?,
    dictionaryContext: List<String>?,
    conversationContext: STTConversationContext?,
): String? = buildList {
    configuredPrompt.trim().takeIf { it.isNotEmpty() }?.let { add("Instructions:\n$it") }
    contentContext?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Content:\n$it") }
    dictionaryContext
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { add("Dictionary:\n$it") }
    conversationContext?.participants
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { add("Participants:\n$it") }
    conversationContext?.messages
        ?.asReversed()
        ?.asSequence()
        ?.mapNotNull { message ->
            message.content.trim().takeIf { it.isNotEmpty() }?.let { "${message.role.name.lowercase()}: $it" }
        }
        ?.take(MAX_CONVERSATION_MESSAGES)
        ?.toList()
        ?.asReversed()
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("\n")
        ?.let { add("Recent conversation:\n$it") }
}.joinToString("\n\n").take(MAX_PROMPT_CHARACTERS).takeIf { it.isNotEmpty() }

internal fun pcm16Wav(
    audio: ByteArray,
    sampleRate: Int,
    encoding: AudioEncoding,
): ByteArray {
    val samples = when (encoding) {
        AudioEncoding.PCM_16BIT -> audio.toShortArray()
        AudioEncoding.PCM_FLOAT_32BIT -> audio.floatPcmToShortArray()
    }
    val resampled = if (sampleRate == TARGET_SAMPLE_RATE) samples else Resampler(sampleRate, TARGET_SAMPLE_RATE).process(samples)
    val pcm16 = resampled.toLittleEndianBytes()
    return Buffer().apply {
        writeWavHeader(TARGET_SAMPLE_RATE, pcm16.size)
        write(pcm16)
    }.readByteArray()
}

private fun ByteArray.toShortArray(): ShortArray = ShortArray(size / 2) { index ->
    val offset = index * 2
    (((this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8))).toShort()
}

private fun ByteArray.floatPcmToShortArray(): ShortArray = ShortArray(size / 4) { index ->
    val offset = index * 4
    val bits = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
    (Float.fromBits(bits) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}

private fun ShortArray.toLittleEndianBytes(): ByteArray = ByteArray(size * 2).also { bytes ->
    forEachIndexed { index, sample ->
        bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
        bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
    }
}

class OpenAITranscriptionService(
    private val coreConfigFlow: CoreConfigFlow,
    private val tokenStorage: IntegrationTokenStorage,
    engine: HttpClientEngine,
) : TranscriptionService {
    private val client = HttpClient(engine)
    private val json = Json { ignoreUnknownKeys = true }
    private val config get() = coreConfigFlow.value.sttConfig.openAI

    override val onInitialized: Channel<Boolean> = Channel()

    override suspend fun isAvailable(): Boolean = config.isConfigured()

    override suspend fun transcribe(
        audioStreamFrames: Flow<ByteArray>?,
        sampleRate: Int,
        language: STTLanguage,
        conversationContext: STTConversationContext?,
        dictionaryContext: List<String>?,
        contentContext: String?,
        encoding: AudioEncoding,
        initialTimeout: Duration?,
    ): Flow<TranscriptionSessionStatus> = flow {
        val configured = config
        val endpoint = configured.transcriptionUrlOrNull()
            ?: throw TranscriptionException.TranscriptionServiceUnavailable(configured.model)
        val frames = audioStreamFrames
            ?: throw TranscriptionException.TranscriptionServiceError("OpenAI transcription requires audio", modelUsed = configured.model)
        emit(TranscriptionSessionStatus.Open)

        val audio = Buffer().also { buffer -> frames.collect { buffer.write(it) } }.readByteArray()
        if (audio.isEmpty()) throw TranscriptionException.NoSpeechDetected("empty_audio", configured.model)
        val wav = pcm16Wav(audio, sampleRate, encoding)

        val response = try {
            client.post(endpoint) {
                tokenStorage.getToken(OPENAI_TRANSCRIPTION_API_KEY_STORAGE_KEY)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::bearerAuth)
                setBody(MultiPartFormDataContent(formData {
                    append("file", wav, Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"audio.wav\"")
                        append(HttpHeaders.ContentType, "audio/wav")
                    })
                    append("model", configured.model.trim())
                    append("response_format", "json")
                    (language as? STTLanguage.Specific)?.languageCodes?.firstOrNull()?.let { append("language", it) }
                    buildOpenAIPrompt(
                        configured.prompt,
                        contentContext,
                        dictionaryContext,
                        conversationContext,
                    )?.let { append("prompt", it) }
                }))
            }
        } catch (e: IOException) {
            throw TranscriptionException.TranscriptionNetworkError(e, configured.model)
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: Exception) {
            throw TranscriptionException.TranscriptionServiceError(
                "OpenAI request failed: ${e.message}", e, configured.model,
            )
        }

        val body = try {
            response.bodyAsText()
        } catch (e: IOException) {
            throw TranscriptionException.TranscriptionNetworkError(e, configured.model)
        }
        if (!response.status.isSuccess()) {
            throw TranscriptionException.TranscriptionServiceError(
                "OpenAI transcription error (${response.status.value}): ${openAIErrorMessage(body)}", modelUsed = configured.model,
            )
        }
        val text = runCatching { json.decodeFromString<OpenAITranscriptionResponse>(body).text }.getOrElse {
            throw TranscriptionException.TranscriptionServiceError("OpenAI returned an invalid response", it, configured.model)
        }.orEmpty().trim()
        if (text.isEmpty()) throw TranscriptionException.NoSpeechDetected("empty_transcript", configured.model)
        emit(TranscriptionSessionStatus.Transcription(text, configured.model))
    }
}

@Serializable
private data class OpenAITranscriptionResponse(val text: String? = null)

@Serializable
private data class OpenAIErrorResponse(val error: OpenAIError? = null)

@Serializable
private data class OpenAIError(val message: String? = null)

private fun openAIErrorMessage(body: String): String =
    runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<OpenAIErrorResponse>(body).error?.message }.getOrNull()
        ?: body.take(512)
