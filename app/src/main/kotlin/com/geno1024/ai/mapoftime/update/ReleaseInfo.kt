package com.geno1024.ai.mapoftime.update

import org.json.JSONArray
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

        /** One release, parsed from a recorded response. No APK means no update. */
        fun parse(json: String): ReleaseInfo? =
            runCatching { parseOne(JSONObject(json)) }.getOrNull()

        /**
         * Reads the `/releases` list. Only the requested channel is considered:
         * the rolling canary is a prerelease, so normal releases never answer a
         * canary check. A release without an APK is skipped; an APK whose stamp
         * cannot be read is a hard `VERSION` error, never "newest wins".
         */
        fun parseList(json: String, prerelease: Boolean = true): List<ReleaseInfo> {
            val array = runCatching { JSONArray(json) }.getOrNull()
                ?: throw UpdateException(UpdateError.UNREADABLE)
            return buildList {
                for (i in 0 until array.length()) {
                    val root = array.optJSONObject(i) ?: continue
                    if (root.optBoolean("prerelease") != prerelease) continue
                    parseOne(root)?.let(::add)
                }
            }
        }

        private fun parseOne(root: JSONObject): ReleaseInfo? {
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

            // The version comes from the built artifact. Release title first,
            // then the tag, then the asset name; each is a text stamp, and an
            // unreadable stamp must be reported instead of guessed.
            val version = listOf(
                root.optString("name"),
                root.optString("tag_name"),
                chosen.name,
            ).firstNotNullOfOrNull { AppVersion.parse(it) }
                ?: throw UpdateException(UpdateError.VERSION)

            return ReleaseInfo(
                version = version,
                // The notes are Markdown; a phone card shows them as plain text.
                notes = root.optString("body").replace("`", "").trim().take(MAX_NOTES),
                publishedAt = publishedAt(root.optString("published_at")),
                asset = chosen,
            )
        }

        private fun publishedAt(raw: String): Long =
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}
