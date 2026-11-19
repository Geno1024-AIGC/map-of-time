package com.geno1024.ai.mapoftime.osm

import com.geno1024.ai.mapoftime.core.Bounds
import com.geno1024.ai.mapoftime.core.LatLon
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * What an Overpass body actually says.
 *
 * Overpass reports a query that timed out or ran out of memory as **HTTP 200
 * with a `remark` and no elements**. Treating that as "this area has no roads"
 * would cache the failure and hide street data for months, so the two cases
 * are kept apart.
 */
sealed interface OverpassAnswer {
    data class Ways(val ways: List<OsmWay>) : OverpassAnswer

    data class Failed(val reason: String) : OverpassAnswer
}

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

    /** Convenience for callers that only care about the elements. */
    fun parse(json: String): List<OsmWay> = when (val answer = parseAnswer(json)) {
        is OverpassAnswer.Ways -> answer.ways
        is OverpassAnswer.Failed -> emptyList()
    }

    fun parseAnswer(json: String): OverpassAnswer {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return OverpassAnswer.Failed("unreadable JSON")
        val remark = root.optString("remark")
        if (remark.isNotBlank()) return OverpassAnswer.Failed(remark)
        return OverpassAnswer.Ways(ways(root))
    }

    private fun ways(root: JSONObject): List<OsmWay> {
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
    fun fetchWays(bounds: Bounds, timeoutSeconds: Int = 30): List<OsmWay> {
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
            // The public instance is routinely busy for half a minute before it
            // even starts computing; hanging on for 150 s helps nobody.
            connectTimeout = 10_000
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
            return when (val answer = OverpassParser.parseAnswer(body)) {
                is OverpassAnswer.Ways -> answer.ways
                is OverpassAnswer.Failed ->
                    throw IOException("Overpass: ${answer.reason}")
            }
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
