package com.geno1024.ai.mapoftime.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams the release APK into the cache directory.
 *
 * The cache is the right home for an installer payload: the system may reclaim
 * it at any time, and losing a download is harmless because the next attempt
 * starts over. Bytes land in `<name>.part` first and are renamed into place only
 * after the size and the ZIP magic pass, so an interrupted download can never be
 * mistaken for a finished APK.
 */
class UpdateDownloader(
    private val userAgent: String = UpdateChecker.USER_AGENT,
) {
    suspend fun download(
        asset: ReleaseAsset,
        directory: File,
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        // An older APK would only sit in the cache; the release is a rolling tag.
        directory.listFiles()?.forEach { it.delete() }

        val target = File(directory, asset.name)
        val connection = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            val expected = connection.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            connection.inputStream.use { input ->
                writeAtomically(input, target, expected, onProgress)
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateError.DOWNLOAD, e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Writes through a sibling `.part` and promotes it only once it is valid.
     * Kept separate from the HTTP call so the on-disk contract can be tested
     * against a plain stream.
     */
    internal fun writeAtomically(
        input: InputStream,
        target: File,
        expectedBytes: Long,
        onProgress: (percent: Int) -> Unit,
    ): File {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        try {
            var copied = 0L
            var reported = -1
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (expectedBytes > 0) {
                        val percent = (copied * 100 / expectedBytes).toInt().coerceIn(0, 100)
                        if (percent != reported) {
                            reported = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (expectedBytes > 0 && copied != expectedBytes) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (!isZip(partial)) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (target.exists() && !target.delete()) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (!partial.renameTo(target)) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
        } catch (e: UpdateException) {
            partial.delete()
            throw e
        } catch (e: IOException) {
            partial.delete()
            throw UpdateException(UpdateError.DOWNLOAD, e)
        }
        onProgress(100)
        return target
    }

    /** An APK is a ZIP: it must start with `PK`. */
    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(2)
            stream.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)
}
