package com.geno1024.ai.mapoftime.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Everything that can go wrong on the way to an update, phrased for the UI. */
enum class UpdateError {
    NETWORK,
    RATE_LIMIT,
    /** The release exists but is not published, so the API hides it from us. */
    UNPUBLISHED,
    UNREADABLE,
    /** The installed version name could not be parsed, so nothing can be compared. */
    VERSION,
    DOWNLOAD,
    INSTALL,
}

class UpdateException(
    val error: UpdateError,
    cause: Throwable? = null,
) : IOException(cause)

/**
 * Reads the rolling `canary` release of this repository.
 *
 * One request, no credentials: the unauthenticated GitHub API allows 60 calls
 * per hour per address, and the app checks at most every few hours. A missing
 * release is reported as `null` so the caller can tell the user why nothing
 * was found instead of silently saying "no update".
 */
class UpdateChecker(
    private val endpoint: String = RELEASE_URL,
    private val userAgent: String = USER_AGENT,
) {
    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) { fetch() }

    private fun fetch(): ReleaseInfo {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    ReleaseInfo.parse(body) ?: throw UpdateException(UpdateError.UNREADABLE)
                }

                // An anonymous API call cannot see drafts. Since CI recreates
                // this release on every build, a 404 usually means the publish
                // step left it as a draft; surface that instead of pretending
                // no build has ever been released.
                HttpURLConnection.HTTP_NOT_FOUND ->
                    throw UpdateException(UpdateError.UNPUBLISHED)

                // Both codes mean the anonymous quota is gone; it refills hourly.
                403, 429 -> throw UpdateException(UpdateError.RATE_LIMIT)

                else -> throw UpdateException(UpdateError.UNREADABLE)
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateError.NETWORK, e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "map-of-time/0.1 (https://github.com/Geno1024-AIGC/map-of-time)"

        const val RELEASE_URL =
            "https://api.github.com/repos/Geno1024-AIGC/map-of-time/releases/tags/canary"
    }
}
