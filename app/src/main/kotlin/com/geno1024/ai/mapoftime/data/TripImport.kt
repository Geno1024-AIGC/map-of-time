package com.geno1024.ai.mapoftime.data

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import org.json.JSONArray
import org.json.JSONObject

/** The current export shape. Older readers saw version 1, which had no segment keys. */
const val EXPORT_FORMAT = "map-of-time/2"

/** One segment definition carried by an export, used to re-link imported legs. */
data class ImportedSegment(
    val key: String,
    val mode: TripMode,
    val name: String?,
    val identity: MatchKind,
    val from: LatLon,
    val to: LatLon,
    val lengthM: Double,
    /** A name the user typed, kept apart from the OSM [name]. */
    val userName: String? = null,
    /** Identities of the two ends, so point names can be restored with them. */
    val fromKey: String? = null,
    val toKey: String? = null,
)

/** One point the user named. */
data class ImportedPoint(val ref: PointRef, val name: String)

/**
 * One junction the user named. Older files have no `places` array at all, so
 * this is optional and those files simply restore the per-point names.
 */
data class ImportedPlace(
    val name: String,
    val center: LatLon,
    val radiusM: Double,
    val points: List<PointRef>,
)

data class ImportedLeg(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    /** Which exported segment this leg belongs to; null in older exports. */
    val segmentKey: String?,
    val direction: Int,
    val label: String?,
    val partial: Boolean,
    val polyline: String,
    val movingMs: Long,
    val stoppedMs: Long,
    val stopCount: Int,
    val longestStopMs: Long,
    val signalStopCount: Int,
    val signalWaitMs: Long,
)

data class ImportedTrip(
    val mode: TripMode,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val status: TripStatus,
    val note: String?,
    val legs: List<ImportedLeg>,
)

data class ImportBundle(
    val segments: List<ImportedSegment>,
    val points: List<ImportedPoint> = emptyList(),
    val places: List<ImportedPlace> = emptyList(),
    val trips: List<ImportedTrip>,
) {
    val legCount: Int get() = trips.sumOf { it.legs.size }
}

/**
 * Reads the JSON the app itself exports.
 *
 * Deliberately synchronous and Android-free so the whole format can be unit
 * tested. Anything unreadable is rejected as a whole — importing half a file
 * would quietly create trips with missing legs. Individual malformed elements
 * inside a valid document are skipped, because one bad row is not a reason to
 * throw away every other trip in it.
 */
object TripImport {
    fun parse(json: String): ImportBundle? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val format = root.optString("format")
        if (format !in SUPPORTED_FORMATS) return null
        return ImportBundle(
            segments = root.optJSONArray("segments").mapObjects { segment(it) },
            points = root.optJSONArray("points").mapObjects { point(it) },
            places = root.optJSONArray("places").mapObjects { place(it) },
            trips = root.optJSONArray("trips").mapObjects { trip(it) },
        )
    }

    private val SUPPORTED_FORMATS = setOf("map-of-time/1", EXPORT_FORMAT)

    private fun segment(objects: JSONObject?): ImportedSegment? {
        val objectValue = objects ?: return null
        val key = objectValue.optString("key").takeIf { it.isNotBlank() } ?: return null
        val mode = modeOf(objectValue.optString("mode")) ?: return null
        return ImportedSegment(
            key = key,
            mode = mode,
            name = objectValue.optStringOrNull("name"),
            identity = MatchKind.of(objectValue.optString("identity")),
            from = LatLon(objectValue.optDouble("fromLat"), objectValue.optDouble("fromLon")),
            to = LatLon(objectValue.optDouble("toLat"), objectValue.optDouble("toLon")),
            lengthM = objectValue.optDouble("lengthM", 0.0).coerceAtLeast(0.0),
            userName = objectValue.optStringOrNull("userName"),
            fromKey = PointRef.parse(objectValue.optStringOrNull("fromKey"))?.key,
            toKey = PointRef.parse(objectValue.optStringOrNull("toKey"))?.key,
        )
    }

    private fun point(objectValue: JSONObject): ImportedPoint? {
        val kind = objectValue.optString("kind")
        val nodeId = objectValue.optLong("nodeId", 0L)
        val name = objectValue.optStringOrNull("name") ?: return null
        val ref = PointRef.parse("$kind:$nodeId") ?: return null
        if (nodeId <= 0) return null
        return ImportedPoint(ref, name)
    }

    private fun place(objectValue: JSONObject): ImportedPlace? {
        val name = objectValue.optStringOrNull("name") ?: return null
        val members = objectValue.optJSONArray("points").mapObjects { point(it) }
        if (members.isEmpty()) return null
        return ImportedPlace(
            name = name,
            center = LatLon(objectValue.optDouble("lat"), objectValue.optDouble("lon")),
            radiusM = objectValue.optDouble("radiusM", 0.0).coerceAtLeast(0.0),
            points = members.map { it.ref },
        )
    }

    private fun trip(objectValue: JSONObject): ImportedTrip? {
        val mode = modeOf(objectValue.optString("mode")) ?: return null
        val startedAt = objectValue.optLong("startedAt", 0L)
        if (startedAt <= 0) return null
        val endedAt = objectValue.optLong("endedAt", 0L).takeIf { it > 0 }
        return ImportedTrip(
            mode = mode,
            startedAt = startedAt,
            endedAt = endedAt,
            distanceM = objectValue.optDouble("distanceM", 0.0).coerceAtLeast(0.0),
            status = runCatching { TripStatus.valueOf(objectValue.optString("status")) }
                .getOrDefault(TripStatus.FINISHED),
            note = objectValue.optStringOrNull("note"),
            legs = objectValue.optJSONArray("legs").mapObjects { leg(it) },
        )
    }

    private fun leg(objectValue: JSONObject): ImportedLeg? {
        val startMs = objectValue.optLong("startMs", 0L)
        val endMs = objectValue.optLong("endMs", 0L)
        if (startMs <= 0 || endMs <= startMs) return null
        return ImportedLeg(
            startMs = startMs,
            endMs = endMs,
            distanceM = objectValue.optDouble("distanceM", 0.0).coerceAtLeast(0.0),
            segmentKey = objectValue.optStringOrNull("segmentKey"),
            direction = objectValue.optInt("direction", 1),
            label = objectValue.optStringOrNull("label"),
            partial = objectValue.optBoolean("partial", false),
            polyline = objectValue.optString("polyline"),
            movingMs = objectValue.optLong("movingMs", endMs - startMs),
            stoppedMs = objectValue.optLong("stoppedMs", 0L),
            stopCount = objectValue.optInt("stopCount", 0),
            longestStopMs = objectValue.optLong("longestStopMs", 0L),
            signalStopCount = objectValue.optInt("signalStopCount", 0),
            signalWaitMs = objectValue.optLong("signalWaitMs", 0L),
        )
    }

    private fun modeOf(id: String): TripMode? = TripMode.entries.firstOrNull { it.id == id }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    private inline fun <T> JSONArray?.mapObjects(parse: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            parse(item)?.let { out += it }
        }
        return out
    }

}
