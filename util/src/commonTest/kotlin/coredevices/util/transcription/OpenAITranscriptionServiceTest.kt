package coredevices.util.transcription

import coredevices.util.AudioEncoding
import coredevices.util.CloudTranscriptionProvider
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.OpenAITranscriptionConfig
import coredevices.util.STTConfig
import coredevices.util.integrations.IntegrationTokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAITranscriptionServiceTest {
    @Test
    fun normalizesV1AndCompleteTranscriptionUrlsAndRejectsInvalidUrls() {
        assertEquals(
            "https://whisper.example/v1/audio/transcriptions",
            OpenAITranscriptionConfig("https://whisper.example/v1/", "whisper-1").transcriptionUrlOrNull(),
        )
        assertEquals(
            "https://whisper.example/v1/audio/transcriptions",
            OpenAITranscriptionConfig("https://whisper.example/v1/audio/transcriptions", "whisper-1").transcriptionUrlOrNull(),
        )
        assertNull(OpenAITranscriptionConfig("http://whisper.example/v1", "whisper-1").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://whisper.example/v1", "").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://user@whisper.example/v1", "whisper-1").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://user:password@whisper.example/v1", "whisper-1").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://whisper.example/v1?tenant=one", "whisper-1").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://whisper.example/v1#transcribe", "whisper-1").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://whisper.example/v1?", "whisper-1").transcriptionUrlOrNull())
        assertNull(OpenAITranscriptionConfig("https://whisper.example/v1#", "whisper-1").transcriptionUrlOrNull())
    }

    @Test
    fun writesMonoPcm16WavHeader() {
        val wav = pcm16Wav(byteArrayOf(1, 0, 2, 0), 16_000, AudioEncoding.PCM_16BIT)

        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertEquals(1, littleEndianShort(wav, 20))
        assertEquals(1, littleEndianShort(wav, 22))
        assertEquals(16_000, littleEndianInt(wav, 24))
        assertEquals(16, littleEndianShort(wav, 34))
        assertEquals(4, littleEndianInt(wav, 40))
    }

    @Test
    fun postsMultipartWithOptionalBearerAuth(): Unit = runBlocking {
        var url = ""
        var authorization: String? = null
        var multipart = false
        val service = service(
            apiKey = "secret",
            engine = MockEngine { request ->
                url = request.url.toString()
                authorization = request.headers[HttpHeaders.Authorization]
                multipart = request.body is MultiPartFormDataContent
                respond(
                    content = ByteReadChannel("{\"text\":\"hello\"}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )

        val result = service.transcribe(flowOf(byteArrayOf(1, 0)), 16_000).toList()

        assertEquals("https://whisper.example/v1/audio/transcriptions", url)
        assertEquals("Bearer secret", authorization)
        assertTrue(multipart)
        assertIs<TranscriptionSessionStatus.Transcription>(result.last())
    }

    @Test
    fun omitsAuthorizationWhenApiKeyIsBlank() = runBlocking {
        var authorization: String? = "unexpected"
        val service = service(
            apiKey = "",
            engine = MockEngine { request ->
                authorization = request.headers[HttpHeaders.Authorization]
                respond(ByteReadChannel("{\"text\":\"hello\"}"), HttpStatusCode.OK)
            },
        )

        service.transcribe(flowOf(byteArrayOf(1, 0)), 16_000).toList()

        assertNull(authorization)
    }

    @Test
    fun mergesPromptContext() {
        val prompt = buildOpenAIPrompt(
            configuredPrompt = "Use product names exactly.",
            dictionaryContext = listOf("Pebble", "", " Rebble "),
            contentContext = "Reply to a message",
            conversationContext = STTConversationContext(
                id = "conversation",
                participants = listOf("Alex", " ", "Jamie"),
                messages = listOf(
                    STTConversationMessage(STTConvoRole.User, "Where are you?"),
                    STTConversationMessage(STTConvoRole.Assistant, "On my way."),
                ),
            ),
        )

        assertEquals(
            "Instructions:\nUse product names exactly.\n\n" +
                "Content:\nReply to a message\n\n" +
                "Dictionary:\nPebble, Rebble\n\n" +
                "Participants:\nAlex, Jamie\n\n" +
                "Recent conversation:\nuser: Where are you?\nassistant: On my way.",
            prompt,
        )
    }

    @Test
    fun mapsNetworkAndHttpFailuresSeparately(): Unit = runBlocking {
        val network = service("", MockEngine { throw IOException("offline") })
        val http = service("", MockEngine {
            respond(ByteReadChannel("{\"error\":{\"message\":\"bad model\"}}"), HttpStatusCode.BadRequest)
        })

        val networkError = runCatching {
            network.transcribe(flowOf(byteArrayOf(1, 0)), 16_000).toList()
        }.exceptionOrNull()
        val httpError = runCatching {
            http.transcribe(flowOf(byteArrayOf(1, 0)), 16_000).toList()
        }.exceptionOrNull()

        assertIs<TranscriptionException.TranscriptionNetworkError>(networkError)
        assertIs<TranscriptionException.TranscriptionServiceError>(httpError)
    }

    @Test
    fun oldSttConfigDefaultsToWispr() {
        val config = Json.decodeFromString<STTConfig>("{}")

        assertEquals(CloudTranscriptionProvider.Wispr, config.cloudProvider)
        assertFalse(config.openAI.isConfigured())
        assertEquals("", config.openAI.prompt)
    }

    private fun service(
        apiKey: String,
        engine: MockEngine,
        openAIConfig: OpenAITranscriptionConfig = OpenAITranscriptionConfig("https://whisper.example/v1", "whisper-1"),
    ): OpenAITranscriptionService {
        val config = CoreConfigFlow(MutableStateFlow(
            CoreConfig(sttConfig = STTConfig(openAI = openAIConfig))
        ))
        return OpenAITranscriptionService(config, FakeTokenStorage(apiKey), engine)
    }

    private class FakeTokenStorage(private val apiKey: String) : IntegrationTokenStorage {
        override suspend fun saveToken(key: String, token: String) = Unit
        override suspend fun getToken(key: String): String? = apiKey
        override suspend fun deleteToken(key: String) = Unit
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
