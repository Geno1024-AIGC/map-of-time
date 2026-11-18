package com.geno1024.ai.mapoftime.osm

import com.geno1024.ai.mapoftime.core.Bounds
import com.geno1024.ai.mapoftime.core.LatLon
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Turns an Overpass `out tags geom` answer into ways.
 *
 * Kept separate from the network call so it can be tested against a recorded
 * response, and so a malformed element never takes the whole answer down.
 */
object OverpassParser {
    /** Ways that cannot be travelled and would only add noise. */
    private val EXCLUDED_HIGHWAYS = setOf(
        "construction",
        "proposed",
        "abandoned",
        "razed",
        "bus_guideway",
        "raceway",
    )

    fun parse(json: String): List<OsmWay> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val ways = ArrayList<OsmWay>(elements.length())
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            if (element.optString("type") != "way") continue
            val tags = element.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway").takeIf { it.isNotBlank() } ?: continue
            if (highway in EXCLUDED_HIGHWAYS) continue
            val nodeIds = longArray(element.optJSONArray("nodes")) ?: continue
            val points = coordinates(element.optJSONArray("geometry")) ?: continue
            if (nodeIds.size != points.size || points.size < 2) continue
            ways += OsmWay(
                id = element.optLong("id"),
                highway = highway,
                name = tags.optString("name").takeIf { it.isNotBlank() },
                ref = tags.optString("ref").takeIf { it.isNotBlank() },
                oneway = tags.optString("oneway") in setOf("yes", "true", "1", "-1"),
                nodeIds = nodeIds.toList(),
                points = points,
            )
        }
        return ways
    }

    private fun longArray(array: org.json.JSONArray?): LongArray? {
        if (array == null) return null
        val out = LongArray(array.length())
        for (i in array.length() - 1 downTo 0) {
            out[i] = array.optLong(i)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun coordinates(array: org.json.JSONArray?): List<LatLon>? {
        if (array == null) return null
        val out = ArrayList<LatLon>(array.length())
        for (i in 0 until array.length()) {
            val point = array.optJSONObject(i) ?: return null
            if (!point.has("lat") || !point.has("lon")) return null
            out += LatLon(point.getDouble("lat"), point.getDouble("lon"))
        }
        return out.takeIf { it.isNotEmpty() }
    }
}

/**
 * Minimal Overpass client. One query per tile keeps each answer small enough to
 * parse comfortably on a phone and lets the cache be per area.
 */
class OverpassClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val userAgent: String = USER_AGENT,
) {
    fun fetchWays(bounds: Bounds, timeoutSeconds: Int = 120): List<OsmWay> {
        val query = buildString {
            append("[out:json][timeout:").append(timeoutSeconds).append("];")
            append("way[\"highway\"](")
            append(bounds.minLat).append(',')
            append(bounds.minLon).append(',')
            append(bounds.maxLat).append(',')
            append(bounds.maxLon)
            append(");out tags geom;")
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = (timeoutSeconds + 30) * 1_000
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { out ->
                out.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Overpass returned HTTP $code")
            }
            val stream = if (connection.contentEncoding?.contains("gzip") == true) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            val body = stream.bufferedReader().use { it.readText() }
            return OverpassParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"

        /** Overpass policy requires an identifiable client. */
        const val USER_AGENT = "map-of-time/0.1 (https://github.com/Geno1024-AIGC/map-of-time)"
    }
}
