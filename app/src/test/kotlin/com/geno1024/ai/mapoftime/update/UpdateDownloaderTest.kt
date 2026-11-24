package com.geno1024.ai.mapoftime.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class UpdateDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val downloader = UpdateDownloader()
    private val apkBytes = "PK\u0003\u0004a signed apk, more bytes".toByteArray()

    @Test
    fun aValidApkIsPromotedOnlyAfterValidation() {
        val target = folder.newFolder("updates").resolve("canary.apk")
        val progress = mutableListOf<Int>()

        val result = downloader.writeAtomically(
            ByteArrayInputStream(apkBytes), target, apkBytes.size.toLong(), progress::add,
        )

        assertEquals(target, result)
        assertArrayEquals(apkBytes, target.readBytes())
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
        assertEquals(100, progress.last())
    }

    @Test
    fun aTruncatedDownloadNeverBecomesAnApk() {
        val target = folder.newFolder("updates").resolve("canary.apk")

        assertDownloadFails(target, apkBytes, expectedBytes = apkBytes.size + 10L)

        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun aCaptivePortalPageIsRejected() {
        val target = folder.newFolder("updates").resolve("canary.apk")
        val html = "<html>please sign in</html>".toByteArray()

        assertDownloadFails(target, html, expectedBytes = html.size.toLong())

        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun aFailedDownloadCannotDestroyThePreviousApk() {
        val target = folder.newFolder("updates").resolve("canary.apk")
        val previous = "PK\u0003\u0004previous build".toByteArray()
        target.writeBytes(previous)

        assertDownloadFails(target, "<html>nope</html>".toByteArray(), expectedBytes = 16L)

        assertArrayEquals(previous, target.readBytes())
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun anUnknownSizeStillValidatesTheZipHeader() {
        val target = folder.newFolder("updates").resolve("canary.apk")
        val progress = mutableListOf<Int>()

        val result = downloader.writeAtomically(
            ByteArrayInputStream(apkBytes), target, 0L, progress::add,
        )

        assertTrue(result.isFile)
        assertEquals(listOf(100), progress)
    }

    @Test
    fun onlySafeApkNamesBecomeFiles() {
        assertEquals(
            "map-of-time-0.1.1.1.abcdef12.apk",
            downloader.safeAssetName("map-of-time-0.1.1.1.abcdef12.apk"),
        )
        assertEquals("map-of-time-update.apk", downloader.safeAssetName("../../escape.apk"))
        assertEquals("map-of-time-update.apk", downloader.safeAssetName("bad name.apk"))
        assertEquals("map-of-time-update.apk", downloader.safeAssetName("x".repeat(125) + ".apk"))
        assertEquals("map-of-time-update.apk", downloader.safeAssetName("notes.txt"))
    }

    private fun assertDownloadFails(target: java.io.File, bytes: ByteArray, expectedBytes: Long) {
        try {
            downloader.writeAtomically(ByteArrayInputStream(bytes), target, expectedBytes) {}
            fail("the download should have been rejected")
        } catch (e: UpdateException) {
            assertEquals(UpdateError.DOWNLOAD, e.error)
        }
    }
}
