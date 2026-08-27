package coredevices.coreapp.util

import coredevices.util.CommonBuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class NumericAppVersion private constructor(
    private val components: List<Int>,
) : Comparable<NumericAppVersion> {
    override fun compareTo(other: NumericAppVersion): Int {
        for (index in 0 until COMPONENT_COUNT) {
            val comparison = componentAt(index).compareTo(other.componentAt(index))
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString(): String = components.joinToString(".")

    private fun componentAt(index: Int): Int = components.getOrElse(index) { 0 }

    companion object {
        private const val MIN_COMPONENT_COUNT = 3
        private const val COMPONENT_COUNT = 4

        fun parse(value: String): NumericAppVersion? {
            val parts = value.split('.')
            if (parts.size !in MIN_COMPONENT_COUNT..COMPONENT_COUNT) return null
            if (parts.any { it.isEmpty() || it.any(Char::isDigit).not() }) return null
            val components = parts.map { it.toIntOrNull() ?: return null }
            return NumericAppVersion(components)
        }
    }
}

data class GithubApkRelease(
    val tag: String,
    val version: NumericAppVersion,
    val downloadUrl: String,
    val sha256: String?,
)

class GithubAppUpdateChecker(
    engine: HttpClientEngine,
    private val repository: String = CommonBuildKonfig.PEBBLEAPP_GITHUB_REPOSITORY,
) {
    private val client = HttpClient(engine)

    suspend fun checkForUpdate(installedVersion: String): GithubApkRelease? {
        val currentVersion = NumericAppVersion.parse(installedVersion) ?: return null
        if (!REPOSITORY_PATTERN.matches(repository)) return null

        val response = client.get("https://api.github.com/repos/$repository/releases/latest") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "PebbleApp/${CommonBuildKonfig.USER_AGENT_VERSION}")
        }
        if (!response.status.isSuccess()) return null

        return parseGithubApkRelease(response.bodyAsText())?.takeIf { it.version > currentVersion }
    }

    companion object {
        private val REPOSITORY_PATTERN = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
    }
}

@Serializable
internal data class GithubReleaseResponse(
    @SerialName("tag_name") val tagName: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubReleaseAsset> = emptyList(),
)

@Serializable
internal data class GithubReleaseAsset(
    val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    val digest: String? = null,
)

internal fun parseGithubApkRelease(response: String): GithubApkRelease? = runCatching {
    RELEASE_JSON.decodeFromString<GithubReleaseResponse>(response)
}.getOrNull()?.let(::selectGithubApkRelease)

internal fun selectGithubApkRelease(release: GithubReleaseResponse): GithubApkRelease? {
    if (release.draft || release.prerelease) return null

    val tag = release.tagName ?: return null
    val version = NumericAppVersion.parse(tag) ?: return null
    val assetName = "PebbleApp-$tag.apk"
    val apkAssets = release.assets.filter {
        it.name == assetName &&
            it.contentType.equals(APK_MIME_TYPE, ignoreCase = true) &&
            it.browserDownloadUrl?.isHttpsUrl() == true
    }
    val asset = apkAssets.singleOrNull() ?: return null
    val sha256 = asset.digest?.let(::normalizeSha256Digest) ?: run {
        if (asset.digest != null) return null
        null
    }

    return GithubApkRelease(
        tag = tag,
        version = version,
        downloadUrl = asset.browserDownloadUrl ?: return null,
        sha256 = sha256,
    )
}

internal fun normalizeSha256Digest(digest: String): String? = SHA256_PATTERN.matchEntire(digest)
    ?.groupValues
    ?.get(1)
    ?.lowercase()

private fun String.isHttpsUrl(): Boolean = runCatching {
    Url(this).protocol == URLProtocol.HTTPS
}.getOrDefault(false)

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private val RELEASE_JSON = Json { ignoreUnknownKeys = true }
private val SHA256_PATTERN = Regex("^sha256:([0-9a-fA-F]{64})$")
