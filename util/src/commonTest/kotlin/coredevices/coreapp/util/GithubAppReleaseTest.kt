package coredevices.coreapp.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubAppReleaseTest {
    @Test
    fun fourComponentVersionComparesNumerically() {
        val current = assertNotNull(NumericAppVersion.parse("1.10.0.5"))
        val available = assertNotNull(NumericAppVersion.parse("1.10.0.6"))

        assertTrue(available > current)
    }

    @Test
    fun missingFourthComponentComparesAsZero() {
        val threeComponent = assertNotNull(NumericAppVersion.parse("1.10.0"))
        val fourComponent = assertNotNull(NumericAppVersion.parse("1.10.0.0"))

        assertEquals(0, threeComponent.compareTo(fourComponent))
    }

    @Test
    fun equalAndOlderVersionsAreNotNewer() {
        val current = assertNotNull(NumericAppVersion.parse("1.10.0.6"))
        val equal = assertNotNull(NumericAppVersion.parse("1.10.0.6"))
        val older = assertNotNull(NumericAppVersion.parse("1.10.0.5"))

        assertFalse(equal > current)
        assertFalse(older > current)
    }

    @Test
    fun malformedVersionsAreRejected() {
        listOf("v1.10.0", "1.10", "1.10.0.0.1", "1.10.x", "1..10.0").forEach {
            assertNull(NumericAppVersion.parse(it))
        }
    }

    @Test
    fun selectsOnlyTheExpectedApkAsset() {
        val release = parseGithubApkRelease(
            """
            {
              "tag_name": "1.10.0.6",
              "assets": [
                {
                  "name": "PebbleApp-1.10.0.6.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/m0wer/PebbleApp/releases/download/1.10.0.6/PebbleApp-1.10.0.6.apk",
                  "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                },
                {
                  "name": "PebbleApp-1.10.0.6.apk.sha256",
                  "content_type": "text/plain",
                  "browser_download_url": "https://github.com/m0wer/PebbleApp/releases/download/1.10.0.6/PebbleApp-1.10.0.6.apk.sha256"
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(release)
        assertEquals("1.10.0.6", release.tag)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", release.sha256)
    }

    @Test
    fun rejectsDraftPrereleaseAndMalformedAssets() {
        val validAsset = GithubReleaseAsset(
            name = "PebbleApp-1.10.0.6.apk",
            contentType = "application/vnd.android.package-archive",
            browserDownloadUrl = "https://example.com/PebbleApp-1.10.0.6.apk",
        )
        assertNull(selectGithubApkRelease(GithubReleaseResponse(tagName = "1.10.0.6", draft = true, assets = listOf(validAsset))))
        assertNull(selectGithubApkRelease(GithubReleaseResponse(tagName = "1.10.0.6", prerelease = true, assets = listOf(validAsset))))
        assertNull(selectGithubApkRelease(GithubReleaseResponse(tagName = "1.10.0.6", assets = listOf(validAsset.copy(name = "other.apk")))))
        assertNull(selectGithubApkRelease(GithubReleaseResponse(tagName = "1.10.0.6", assets = listOf(validAsset.copy(digest = "sha256:not-a-digest")))))
        assertNotNull(selectGithubApkRelease(GithubReleaseResponse(tagName = "1.10.0.6", assets = listOf(validAsset))))
    }
}
