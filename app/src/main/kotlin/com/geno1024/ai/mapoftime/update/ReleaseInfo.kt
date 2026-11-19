package com.geno1024.ai.mapoftime.update

import org.json.JSONObject
import java.time.OffsetDateTime

/** The one APK a release ships. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * What the app needs from a GitHub release: which version it is, where the APK
 * lives, and what changed. Parsed separately from the HTTP call so it can be
 * tested against a recorded answer.
 */
data class ReleaseInfo(
    val version: AppVersion,
    val notes: String,
    val publishedAt: Long,
    val asset: ReleaseAsset,
) {
    companion object {
        /** Long release notes would only be truncated on screen anyway. */
        private const val MAX_NOTES = 2_000

        fun parse(json: String): ReleaseInfo? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

            val assets = root.optJSONArray("assets") ?: return null
            var asset: ReleaseAsset? = null
            for (i in 0 until assets.length()) {
                val candidate = assets.optJSONObject(i) ?: continue
                val name = candidate.optString("name")
                val url = candidate.optString("browser_download_url")
                if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
                // Prefer our own naming so a stray extra asset cannot win.
                if (asset == null || name.startsWith("map-of-time-")) {
                    asset = ReleaseAsset(
                        name = name,
                        downloadUrl = url,
                        sizeBytes = candidate.optLong("size", 0L),
                    )
                    if (name.startsWith("map-of-time-")) break
                }
            }
            val chosen = asset ?: return null

            // The file name carries the stamp; the release title does too, so
            // either one is a valid fallback.
            val version = AppVersion.parse(chosen.name)
                ?: AppVersion.parse(root.optString("name"))
                ?: return null

            return ReleaseInfo(
                version = version,
                notes = root.optString("body").trim().take(MAX_NOTES),
                publishedAt = publishedAt(root.optString("published_at")),
                asset = chosen,
            )
        }

        private fun publishedAt(raw: String): Long =
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}
