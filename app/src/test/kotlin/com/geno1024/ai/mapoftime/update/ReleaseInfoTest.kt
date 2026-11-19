package com.geno1024.ai.mapoftime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    private val release = """
        {
          "tag_name": "canary",
          "name": "Map of Time 0.1.37.7.82fdc33a canary",
          "prerelease": true,
          "published_at": "2026-11-18T22:55:33Z",
          "target_commitish": "82fdc33a7ce5d91c92165fad9bc3993fd9238246",
          "body": "Build #13, version 0.1.37.7.82fdc33a.\n\nSigned canary APK.",
          "assets": [
            {
              "name": "map-of-time-0.1.37.7.82fdc33a-build13-82fdc33a.apk",
              "size": 1440226,
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/Geno1024-AIGC/map-of-time/releases/download/canary/map-of-time-0.1.37.7.82fdc33a-build13-82fdc33a.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheVersionTheAssetNameCarries() {
        val info = ReleaseInfo.parse(release)!!
        assertEquals(AppVersion(0, 1, 37, 7, "82fdc33a"), info.version)
        assertEquals(1_440_226L, info.asset.sizeBytes)
        assertTrue(info.asset.downloadUrl.endsWith(".apk"))
        assertTrue(info.notes.startsWith("Build #13"))
        assertTrue(info.publishedAt > 0)
    }

    @Test
    fun prefersOurOwnAssetOverAnExtraOne() {
        val withExtra = release.replace(
            "\"assets\": [",
            "\"assets\": [{\"name\": \"checksums.txt\", \"browser_download_url\": \"https://x/y.txt\"},",
        )
        assertEquals("map-of-time-0.1.37.7.82fdc33a-build13-82fdc33a.apk", ReleaseInfo.parse(withExtra)!!.asset.name)
    }

    @Test
    fun aReleaseWithoutAnApkIsNotAnUpdate() {
        val noApk = release.replace(".apk", ".txt")
        assertNull(ReleaseInfo.parse(noApk))
        assertNull(ReleaseInfo.parse("{}"))
        assertNull(ReleaseInfo.parse("not json"))
    }

    @Test
    fun aBrokenDateDoesNotDiscardTheRelease() {
        val noDate = release.replace("2026-11-18T22:55:33Z", "yesterday")
        val info = ReleaseInfo.parse(noDate)!!
        assertEquals(0L, info.publishedAt)
        assertFalse(info.asset.downloadUrl.isBlank())
    }
}
