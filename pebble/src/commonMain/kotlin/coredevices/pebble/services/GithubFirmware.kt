package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.util.CommonBuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

class GithubFirmware(
    private val httpClient: HttpClient,
    private val repository: String = CommonBuildKonfig.PEBBLEOS_GITHUB_REPOSITORY,
) {
    private val logger = Logger.withTag("GithubFirmware")

    suspend fun getLatestFirmware(
        watch: WatchInfo,
        useCiBuilds: Boolean,
    ): FirmwareUpdateCheckResult {
        return try {
            if (useCiBuilds) {
                latestCiUpdateOrNull(watch)?.let { return it }
            }
            latestRelease().toUpdateResult(watch, isCiRelease = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Failed to check GitHub firmware releases: ${e.message}" }
            FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
    }

    private suspend fun latestCiUpdateOrNull(watch: WatchInfo): FirmwareUpdateCheckResult? = try {
        latestCiRelease()?.toUpdateResult(watch, isCiRelease = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "Failed to use GitHub CI firmware release: ${e.message}; falling back to stable" }
        null
    }

    private suspend fun latestRelease(): GithubRelease = fetch("releases/latest")

    private suspend fun latestCiRelease(): GithubRelease? = fetch<List<GithubRelease>>("releases?per_page=100")
        .asSequence()
        .filter { !it.draft && it.prerelease && it.tagName.startsWith(CI_TAG_PREFIX) }
        .map { it to it.timestamp() }
        .maxByOrNull { it.second }
        ?.first

    private suspend inline fun <reified T> fetch(path: String): T {
        val response = httpClient.get("https://api.github.com/repos/$repository/$path")
        if (response.status != HttpStatusCode.OK) {
            throw GithubFirmwareException("GitHub returned ${response.status}")
        }
        return response.body()
    }

    private fun GithubRelease.toUpdateResult(
        watch: WatchInfo,
        isCiRelease: Boolean,
    ): FirmwareUpdateCheckResult {
        val versionTag = if (isCiRelease) {
            name?.takeIf { it.startsWith(CI_NAME_PREFIX) }?.removePrefix(CI_NAME_PREFIX)
                ?: throw GithubFirmwareException("CI release has an invalid name")
        } else {
            tagName
        }
        if (!VERSION_TAG.matches(versionTag)) {
            throw GithubFirmwareException("Release has an invalid firmware version")
        }
        val version = FirmwareVersion.from(
            tag = versionTag,
            isRecovery = false,
            gitHash = "",
            timestamp = timestamp(),
            isDualSlot = false,
            isSlot0 = false,
        ) ?: throw GithubFirmwareException("Release has an invalid firmware version")
        val assetName = "PebbleOS-${watch.platform.revision}.pbz"
        val matchingAssets = assets.filter { it.name == assetName }
        if (matchingAssets.size != 1) {
            throw GithubFirmwareException("Release has no unique firmware asset for ${watch.platform.revision}")
        }
        val downloadUrl = matchingAssets.single().browserDownloadUrl
        val url = try {
            Url(downloadUrl)
        } catch (e: IllegalArgumentException) {
            throw GithubFirmwareException("Release asset has an invalid download URL")
        }
        if ((url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) || url.host.isBlank()) {
            throw GithubFirmwareException("Release asset download URL is not HTTP(S)")
        }
        return if (
            !watch.runningFwVersion.isRecovery &&
            (normalizedVersion(version) == normalizedVersion(watch.runningFwVersion) || version <= watch.runningFwVersion)
        ) {
            FirmwareUpdateCheckResult.FoundNoUpdate
        } else {
            FirmwareUpdateCheckResult.FoundUpdate(
                version = version,
                notes = body.orEmpty(),
                url = downloadUrl,
            )
        }
    }

    private fun GithubRelease.timestamp(): Instant {
        val value = publishedAt ?: createdAt
            ?: throw GithubFirmwareException("Release has no publication timestamp")
        return try {
            Instant.parse(value)
        } catch (e: IllegalArgumentException) {
            throw GithubFirmwareException("Release has an invalid publication timestamp")
        }
    }

    private fun normalizedVersion(version: FirmwareVersion): String = buildString {
        append(version.major)
        append('.')
        append(version.minor)
        append('.')
        append(version.patch)
        version.suffix.takeIf { !it.isNullOrEmpty() }?.let {
            append('-')
            append(it)
        }
    }

    private class GithubFirmwareException(message: String) : Exception(message)

    private companion object {
        const val CI_TAG_PREFIX = "ci-main-"
        const val CI_NAME_PREFIX = "PebbleOS CI "
        val VERSION_TAG = Regex("^v?[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-[^\\s]+)?$")
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList(),
)

@Serializable
private data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)
