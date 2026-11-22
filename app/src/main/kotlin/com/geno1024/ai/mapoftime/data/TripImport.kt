package com.geno1024.ai.mapoftime.data

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import org.json.JSONArray
import org.json.JSONObject

const val EXPORT_FORMAT = "map-of-time/5"

data class ImportedEdge(
    val key: String,
    val mode: TripMode,
    val name: String?,
    val identity: MatchKind,
    val from: LatLon,
    val to: LatLon,
    val lengthM: Double,
    val userName: String? = null,
    val fromKey: String? = null,
    val toKey: String? = null,
    val roadName: String? = null,
    val roadNames: List<String> = emptyList(),
)

typealias ImportedSegment = ImportedEdge

data class ImportedNode(
    val ref: PointRef,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val junctionName: String? = null,
)

data class ImportedRoad(
    val name: String,
    val source: String = "user",
)

data class ImportedPoint(val ref: PointRef, val name: String)
data class ImportedPointRoads(val ref: PointRef, val roads: List<String>)

data class ImportedPlace(
    val name: String,
    val center: LatLon,
    val radiusM: Double,
    val points: List<PointRef>,
    val kind: PlaceKind = PlaceKind.CROSSING,
)
typealias ImportedJunction = ImportedPlace

data class ImportedLeg(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val edgeKey: String?,
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
) {
    val segmentKey: String? get() = edgeKey
}

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
    val edges: List<ImportedEdge> = emptyList(),
    val nodes: List<ImportedNode> = emptyList(),
    val roads: List<ImportedRoad> = emptyList(),
    val junctions: List<ImportedJunction> = emptyList(),
    val points: List<ImportedPoint> = emptyList(),
    val pointRoads: List<ImportedPointRoads> = emptyList(),
    val places: List<ImportedPlace> = emptyList(),
    val trips: List<ImportedTrip> = emptyList(),
) {
    val segments: List<ImportedSegment> get() = edges
    val legCount: Int get() = trips.sumOf { it.legs.size }
}

object TripImport {
    fun parse(json: String): ImportBundle? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val format = root.optString("format")
        if (format !in SUPPORTED_FORMATS) return null
        val edges = root.optJSONArray("edges").mapObjects { edge(it) }
            .ifEmpty { root.optJSONArray("segments").mapObjects { edge(it) } }
        val junctions = root.optJSONArray("junctions").mapObjects { place(it) }
            .ifEmpty { root.optJSONArray("places").mapObjects { place(it) } }
        return ImportBundle(
            edges = edges,
            nodes = root.optJSONArray("nodes").mapObjects { node(it) },
            roads = root.optJSONArray("roads").mapObjects { road(it) },
            junctions = junctions,
            points = root.optJSONArray("points").mapObjects { point(it) },
            pointRoads = root.optJSONArray("pointRoads").mapObjects { pointRoads(it) },
            places = junctions,
            trips = root.optJSONArray("trips").mapObjects { trip(it) },
        )
    }

    private val SUPPORTED_FORMATS = setOf("map-of-time/1", "map-of-time/2", "map-of-time/3", "map-of-time/4", EXPORT_FORMAT)

    private fun edge(objectValue: JSONObject): ImportedEdge? {
        val key = objectValue.optString("key").takeIf { it.isNotBlank() } ?: return null
        val mode = modeOf(objectValue.optString("mode")) ?: return null
        return ImportedEdge(
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
            roadName = objectValue.optStringOrNull("roadName"),
            roadNames = objectValue.optJSONArray("roadNames")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotEmpty() } }
            }.orEmpty(),
        )
    }

    private fun node(objectValue: JSONObject): ImportedNode? {
        val ref = refOf(objectValue) ?: return null
        return ImportedNode(
            ref = ref,
            name = objectValue.optStringOrNull("name"),
            lat = objectValue.optDoubleOrNull("lat"),
            lon = objectValue.optDoubleOrNull("lon"),
            junctionName = objectValue.optStringOrNull("junctionName"),
        )
    }

    private fun road(objectValue: JSONObject): ImportedRoad? {
        val name = objectValue.optStringOrNull("name") ?: return null
        return ImportedRoad(name = name, source = objectValue.optStringOrNull("source") ?: "user")
    }

    private fun point(objectValue: JSONObject): ImportedPoint? {
        val ref = refOf(objectValue) ?: return null
        val name = objectValue.optStringOrNull("name") ?: return null
        return ImportedPoint(ref, name)
    }

    private fun pointRoads(objectValue: JSONObject): ImportedPointRoads? {
        val ref = refOf(objectValue) ?: return null
        val roads = objectValue.optJSONArray("roads")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }.distinct()
        }.orEmpty()
        return ImportedPointRoads(ref, roads)
    }

    private fun place(objectValue: JSONObject): ImportedPlace? {
        val name = objectValue.optStringOrNull("name") ?: return null
        val members = objectValue.optJSONArray("points").mapObjects { refOf(it) }
        if (members.isEmpty()) return null
        return ImportedPlace(
            name = name,
            center = LatLon(objectValue.optDouble("lat"), objectValue.optDouble("lon")),
            radiusM = objectValue.optDouble("radiusM", 0.0).coerceAtLeast(0.0),
            points = members,
            kind = PlaceKind.of(objectValue.optStringOrNull("kind")),
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
            edgeKey = objectValue.optStringOrNull("edgeKey") ?: objectValue.optStringOrNull("segmentKey"),
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

    private fun refOf(objectValue: JSONObject): PointRef? {
        val kind = objectValue.optString("kind")
        val nodeId = objectValue.optLong("nodeId", 0L)
        if (nodeId <= 0) return null
        return PointRef.parse("$kind:$nodeId")
    }

    private fun modeOf(id: String): TripMode? = TripMode.entries.firstOrNull { it.id == id }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

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
