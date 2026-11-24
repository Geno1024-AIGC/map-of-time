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
 *
 * Release metadata is always read from the GitHub API. Only this file transfer
 * may use a mirror: the chosen source is tried first, then the presets in order,
 * so one dead domain cannot block an update. The asset name is allow-listed
 * before it becomes a file name (or a URL segment in other callers).
 */
class UpdateDownloader(
    private val userAgent: String = UpdateChecker.USER_AGENT,
) {
    /** A GitHub asset name becomes a file name, so only a strict allow-list is safe. */
    private val safeNamePattern = Regex("""[A-Za-z0-9._-]{1,124}\.apk""", RegexOption.IGNORE_CASE)
    private val FALLBACK_NAME = "map-of-time-update.apk"

    /** Rejects path separators, spaces and overlong names with a fixed file name. */
    internal fun safeAssetName(raw: String): String =
        raw.takeIf { safeNamePattern.matches(it) } ?: FALLBACK_NAME

    suspend fun download(
        asset: ReleaseAsset,
        directory: File,
        mirrors: List<Mirror>,
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        // An older APK would only sit in the cache; the release is a rolling tag.
        directory.listFiles()?.forEach { it.delete() }

        val payload = asset.copy(name = safeAssetName(asset.name))
        val candidates = mirrors.distinctBy { it.prefix }.ifEmpty { listOf(Mirrors.DIRECT) }
        var failure: Throwable? = null
        for (mirror in candidates) {
            try {
                return@withContext fetch(mirror.wrap(payload.downloadUrl), payload, directory, onProgress)
            } catch (e: UpdateException) {
                failure = e
            } catch (e: IOException) {
                failure = UpdateException(UpdateError.DOWNLOAD, e)
            }
        }
        throw failure as? UpdateException ?: UpdateException(UpdateError.DOWNLOAD)
    }

    /** One source only; every failure is recoverable by trying the next mirror. */
    private fun fetch(url: String, asset: ReleaseAsset, directory: File, onProgress: (Int) -> Unit): File {
        val target = File(directory, asset.name)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
            // The API's asset size is the authority; a mirror's Content-Length
            // is only a fallback for progress when the API omitted it.
            val expected = asset.sizeBytes.takeIf { it > 0 }
                ?: connection.contentLengthLong.takeIf { it > 0 }
                ?: 0L
            connection.inputStream.use { input ->
                writeAtomically(input, target, expected, onProgress)
            }
            return target
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
