package coredevices.pebble.services

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class GithubFirmwareTest {
    @Test
    fun stableReleaseUsesExactBoardAsset() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.4", assetUrl = "https://download.example/board.pbz"),
        ),
    ) { firmware, requests ->
        val result = firmware.getLatestFirmware(watch(), useCiBuilds = false)

        assertEquals(listOf("/repos/test/PebbleOS/releases/latest"), requests)
        assertEquals("https://download.example/board.pbz", assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result).url)
    }

    @Test
    fun sameNormalizedVersionHasNoUpdate() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.3"),
        ),
    ) { firmware, _ ->
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
    }

    @Test
    fun ciModeUsesNewestCiPrerelease() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to """
                [${release("ci-main-old", name = "PebbleOS CI v1.2.4", publishedAt = "2026-01-01T00:00:00Z")},
                ${release("ci-main-new", name = "PebbleOS CI v1.2.5", publishedAt = "2026-01-02T00:00:00Z")}]
            """.trimIndent(),
        ),
    ) { firmware, requests ->
        val result = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = true))

        assertEquals(listOf("/repos/test/PebbleOS/releases?per_page=100"), requests)
        assertEquals("v1.2.5", result.version.stringVersion)
    }

    @Test
    fun ciModeFallsBackToStableWhenNoCiPrereleaseExists() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to "[${release("v1.2.4", prerelease = false)}]",
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.4"),
        ),
    ) { firmware, requests ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = true))
        assertEquals(
            listOf("/repos/test/PebbleOS/releases?per_page=100", "/repos/test/PebbleOS/releases/latest"),
            requests,
        )
    }

    @Test
    fun ciModeFallsBackToStableWhenCiReleaseIsUnusable() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to
                "[${release("ci-main-bad", name = "PebbleOS CI v1.2.4", assetName = "wrong.pbz")} ]",
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.4"),
        ),
    ) { firmware, requests ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = true))
        assertEquals(
            listOf("/repos/test/PebbleOS/releases?per_page=100", "/repos/test/PebbleOS/releases/latest"),
            requests,
        )
    }

    @Test
    fun malformedReleaseIsRejected() {
        runGithubFirmwareTest(
            responses = mapOf("/repos/test/PebbleOS/releases/latest" to release("not-a-version")),
        ) { firmware, _ ->
            assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        }
        runGithubFirmwareTest(
            responses = mapOf("/repos/test/PebbleOS/releases/latest" to "{"),
        ) { firmware, _ ->
            assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        }
    }

    @Test
    fun invalidBoardAssetIsRejected() {
        runGithubFirmwareTest(
            responses = mapOf("/repos/test/PebbleOS/releases/latest" to release("v1.2.4", assetName = "wrong.pbz")),
        ) { firmware, _ ->
            assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        }
        runGithubFirmwareTest(
            responses = mapOf("/repos/test/PebbleOS/releases/latest" to release("v1.2.4", duplicateAsset = true)),
        ) { firmware, _ ->
            assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        }
        runGithubFirmwareTest(
            responses = mapOf("/repos/test/PebbleOS/releases/latest" to release("v1.2.4", assetUrl = "file:///firmware.pbz")),
        ) { firmware, _ ->
            assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        }
    }

    @Test
    fun httpFailureIsRejected() = runGithubFirmwareTest(
        responses = emptyMap(),
        status = HttpStatusCode.InternalServerError,
    ) { firmware, _ ->
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
    }

    private fun runGithubFirmwareTest(
        responses: Map<String, String>,
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (GithubFirmware, List<String>) -> Unit,
    ) {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += buildString {
                append(request.url.encodedPath)
                request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let {
                    append('?')
                    append(it)
                }
            }
            respond(
                content = responses[request.url.encodedPath].orEmpty(),
                status = if (responses.containsKey(request.url.encodedPath)) HttpStatusCode.OK else status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        kotlinx.coroutines.test.runTest {
            block(GithubFirmware(client, "test/PebbleOS"), requests)
        }
    }

    private fun release(
        tag: String,
        name: String? = null,
        prerelease: Boolean = true,
        publishedAt: String = "2026-01-01T00:00:00Z",
        assetName: String = "PebbleOS-${watch().platform.revision}.pbz",
        assetUrl: String = "https://download.example/firmware.pbz",
        duplicateAsset: Boolean = false,
    ): String {
        val asset = """{"name":"$assetName","browser_download_url":"$assetUrl"}"""
        return """{"tag_name":"$tag","name":${name?.let { "\"$it\"" } ?: "null"},"body":"Notes","draft":false,"prerelease":$prerelease,"published_at":"$publishedAt","assets":[${listOf(asset, asset).take(if (duplicateAsset) 2 else 1).joinToString()}]}"""
    }

    private fun watch(): WatchInfo = WatchInfo(
        runningFwVersion = FirmwareVersion.from(
            tag = "1.2.3",
            isRecovery = false,
            gitHash = "",
            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
            isDualSlot = false,
            isSlot0 = false,
        )!!,
        recoveryFwVersion = null,
        platform = WatchHardwarePlatform.CORE_ASTERIX,
        bootloaderTimestamp = Instant.DISTANT_PAST,
        board = "asterix",
        serial = "123456789012",
        btAddress = "00:11:22:33:44:55",
        resourceCrc = 0,
        resourceTimestamp = Instant.DISTANT_PAST,
        language = "en_US",
        languageVersion = 1,
        capabilities = emptySet(),
        isUnfaithful = false,
        healthInsightsVersion = null,
        javascriptVersion = null,
        color = WatchColor.ClassicFlyBlue,
    )
}
