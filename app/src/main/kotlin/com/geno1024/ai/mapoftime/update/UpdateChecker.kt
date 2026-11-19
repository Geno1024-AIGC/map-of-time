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
    UNREADABLE,
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
 * release (404) is a normal state during the seconds when CI recreates the
 * tag, so it is reported as `null` rather than as a failure.
 */
class UpdateChecker(
    private val endpoint: String = RELEASE_URL,
    private val userAgent: String = USER_AGENT,
) {
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) { fetch() }

    private fun fetch(): ReleaseInfo? {
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

                HttpURLConnection.HTTP_NOT_FOUND -> null

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
