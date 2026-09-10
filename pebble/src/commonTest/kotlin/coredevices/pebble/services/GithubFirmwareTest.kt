package coredevices.pebble.services

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPath
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
            commitPath("v1.2.4") to commit(),
        ),
    ) { firmware, requests ->
        val result = firmware.getLatestFirmware(watch(), useCiBuilds = false)

        assertEquals(listOf("/repos/test/PebbleOS/releases/latest", commitPath("v1.2.4")), requests)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("https://download.example/board.pbz", update.url)
        assertEquals(DEFAULT_GIT_HASH, update.version.gitHash)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), update.version.timestamp)
    }

    @Test
    fun sameNormalizedVersionHasNoUpdate() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.3", publishedAt = "2026-02-01T00:00:00Z"),
            commitPath("v1.2.3") to commit(),
        ),
    ) { firmware, requests ->
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        assertEquals(listOf("/repos/test/PebbleOS/releases/latest", commitPath("v1.2.3")), requests)
    }

    @Test
    fun ciModeUsesNewestCiPrerelease() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to """
                [${release("ci-main-old", name = "PebbleOS CI v1.2.4", publishedAt = "2026-01-01T00:00:00Z")},
                ${release("ci-main-new", name = "PebbleOS CI v1.2.5", publishedAt = "2026-01-02T00:00:00Z")}]
            """.trimIndent(),
            commitPath("ci-main-new") to commit(),
        ),
    ) { firmware, requests ->
        val result = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = true))

        assertEquals(listOf("/repos/test/PebbleOS/releases?per_page=100", commitPath("ci-main-new")), requests)
        assertEquals("v1.2.5", result.version.stringVersion)
    }

    @Test
    fun ciModeFallsBackToStableWhenNoCiPrereleaseExists() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to "[${release("v1.2.4", prerelease = false)}]",
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.4"),
            commitPath("v1.2.4") to commit(),
        ),
    ) { firmware, requests ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = true))
        assertEquals(
            listOf("/repos/test/PebbleOS/releases?per_page=100", "/repos/test/PebbleOS/releases/latest", commitPath("v1.2.4")),
            requests,
        )
    }

    @Test
    fun ciModeFallsBackToStableWhenCiReleaseIsUnusable() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to
                "[${release("ci-main-bad", name = "PebbleOS CI v1.2.4", assetName = "wrong.pbz")} ]",
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.4"),
            commitPath("v1.2.4") to commit(),
        ),
    ) { firmware, requests ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = true))
        assertEquals(
            listOf("/repos/test/PebbleOS/releases?per_page=100", "/repos/test/PebbleOS/releases/latest", commitPath("v1.2.4")),
            requests,
        )
    }

    @Test
    fun sameCommitHasNoUpdateWithDifferentCiLabel() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases" to
                "[${release("ci-main-123", name = "PebbleOS CI v1.2.4", publishedAt = "2026-02-01T00:00:00Z")} ]",
            commitPath("ci-main-123") to commit(),
        ),
    ) { firmware, _ ->
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(
            firmware.getLatestFirmware(watch(gitHash = DEFAULT_GIT_HASH.take(7)), useCiBuilds = true),
        )
    }

    @Test
    fun olderCommitHasNoUpdateWhenInstalledSuffixSharesItsBaseVersion() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.3", publishedAt = "2026-02-01T00:00:00Z"),
            commitPath("v1.2.3") to commit(date = "2026-01-01T00:00:00Z"),
        ),
    ) { firmware, _ ->
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(
            firmware.getLatestFirmware(
                watch(tag = "1.2.3-custom", timestamp = Instant.parse("2026-01-02T00:00:00Z")),
                useCiBuilds = false,
            ),
        )
    }

    @Test
    fun newerCommitProducesUpdate() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.3", publishedAt = "2026-01-01T00:00:00Z"),
            commitPath("v1.2.3") to commit(date = "2026-01-03T00:00:00Z"),
        ),
    ) { firmware, _ ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            firmware.getLatestFirmware(
                watch(tag = "1.2.3-custom", timestamp = Instant.parse("2026-01-02T00:00:00Z")),
                useCiBuilds = false,
            ),
        )
    }

    @Test
    fun recoveryFirmwareOffersUpdateForSameCommit() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.3"),
            commitPath("v1.2.3") to commit(),
        ),
    ) { firmware, _ ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            firmware.getLatestFirmware(watch(gitHash = DEFAULT_GIT_HASH.take(7), isRecovery = true), useCiBuilds = false),
        )
    }

    @Test
    fun missingOrMalformedCommitDataIsRejected() {
        listOf(
            commit(sha = null),
            commit(sha = "not-a-sha"),
            commit(date = null),
            commit(date = "not-a-date"),
        ).forEach { commit ->
            runGithubFirmwareTest(
                responses = mapOf(
                    "/repos/test/PebbleOS/releases/latest" to release("v1.2.4"),
                    commitPath("v1.2.4") to commit,
                ),
            ) { firmware, _ ->
                assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
            }
        }
    }

    @Test
    fun commitTagIsPathEncoded() = runGithubFirmwareTest(
        responses = mapOf(
            "/repos/test/PebbleOS/releases/latest" to release("v1.2.4-feature/test"),
            commitPath("v1.2.4-feature/test") to commit(),
        ),
    ) { firmware, requests ->
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(firmware.getLatestFirmware(watch(), useCiBuilds = false))
        assertEquals(
            listOf("/repos/test/PebbleOS/releases/latest", commitPath("v1.2.4-feature/test")),
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

    private fun commit(
        sha: String? = DEFAULT_GIT_HASH,
        date: String? = "2026-01-01T00:00:00Z",
    ): String = buildString {
        append('{')
        sha?.let { append("\"sha\":\"$it\",") }
        append("\"commit\":{\"committer\":{")
        date?.let { append("\"date\":\"$it\"") }
        append("}}}")
    }

    private fun commitPath(tag: String): String =
        "/repos/test/PebbleOS/commits/${tag.encodeURLPath(encodeSlash = true)}"

    private fun watch(
        tag: String = "1.2.3",
        gitHash: String = "",
        timestamp: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        isRecovery: Boolean = false,
    ): WatchInfo = WatchInfo(
        runningFwVersion = FirmwareVersion.from(
            tag = tag,
            isRecovery = isRecovery,
            gitHash = gitHash,
            timestamp = timestamp,
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

    private companion object {
        const val DEFAULT_GIT_HASH = "0123456789abcdef0123456789abcdef01234567"
    }
}
