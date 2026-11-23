package com.geno1024.ai.mapoftime.osm

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** One OSM way, flattened to the two things we need: where it goes and what it is called. */
data class OsmWay(
    val id: Long,
    val highway: String,
    val name: String?,
    val ref: String?,
    val oneway: Boolean,
    val nodeIds: List<Long>,
    val points: List<LatLon>,
) {
    val label: String
        get() = name ?: ref ?: highway
}

/**
 * A node where three or more road arms meet. That is the definition of an
 * intersection, and it is what the app treats as a segment boundary — a node
 * that merely splits one road in two (degree 2) is not one.
 */
data class OsmJunction(
    val id: Long,
    val at: LatLon,
    val degree: Int,
)

data class RoadLabel(val name: String, val distanceMeters: Double)

/**
 * A small in-memory index over the ways of the tiles a trip touches. Rebuilt
 * per trip; a city-centre tile has a few thousand ways, so this stays cheap.
 */
class RoadGraph(ways: List<OsmWay>) {
    val ways: List<OsmWay> = ways.distinctBy { it.id }

    private val coordinates = HashMap<Long, LatLon>()
    private val walkNeighbours = HashMap<Long, MutableSet<Long>>()
    private val vehicleNeighbours = HashMap<Long, MutableSet<Long>>()
    private val junctionIndex = GridIndex<OsmJunction>(150.0)
    private val vehicleJunctionIndex = GridIndex<OsmJunction>(150.0)
    private val segmentIndex = GridIndex<LabeledSegment>(80.0)

    /** Junctions on the full network: a pedestrian can cross roads and use footways. */
    val junctions: List<OsmJunction>
    /**
     * Junctions a car or bike sees. Footway-only arms are absent, so a zebra
     * mid-block is not one of these — driving over it must not cut a segment.
     */
    val vehicleJunctions: List<OsmJunction>

    init {
        for (way in this.ways) {
            val count = minOf(way.nodeIds.size, way.points.size)
            for (i in 0 until count) {
                coordinates.putIfAbsent(way.nodeIds[i], way.points[i])
            }
            val driveable = RoadAccess.vehicleWay(way.highway)
            for (i in 1 until count) {
                val a = way.nodeIds[i - 1]
                val b = way.nodeIds[i]
                addArm(walkNeighbours, a, b)
                if (driveable) addArm(vehicleNeighbours, a, b)
            }
            for (i in 1 until count) {
                segmentIndex.addSegment(way.points[i - 1], way.points[i], LabeledSegment(way.points[i - 1], way.points[i], way.label))
            }
        }

        junctions = junctionsOf(walkNeighbours)
        vehicleJunctions = junctionsOf(vehicleNeighbours)
        junctions.forEach { junctionIndex.add(it.at, it) }
        vehicleJunctions.forEach { vehicleJunctionIndex.add(it.at, it) }
    }

    private fun addArm(adjacency: HashMap<Long, MutableSet<Long>>, a: Long, b: Long) {
        adjacency.getOrPut(a) { HashSet() } += b
        adjacency.getOrPut(b) { HashSet() } += a
    }

    private fun junctionsOf(adjacency: Map<Long, Set<Long>>): List<OsmJunction> =
        adjacency.filter { it.value.size >= 3 }
            .mapNotNull { (id, around) -> coordinates[id]?.let { OsmJunction(id, it, around.size) } }

    /**
     * Closest intersection within [maxMeters] for this traveller, or `null`
     * when the trace is off the mapped road. Defaults to the full network so
     * callers that have not thought about modes keep their old behaviour.
     */
    fun nearestJunction(
        point: LatLon,
        maxMeters: Double = 30.0,
        traveler: Traveler = Traveler.WALK,
    ): OsmJunction? {
        val index = if (traveler == Traveler.VEHICLE) vehicleJunctionIndex else junctionIndex
        return index.nearest(point, maxMeters) { Geo.distanceMeters(point, it.at) }?.first
    }

    /** Most frequent road label around [points], by point count. */
    fun dominantLabel(points: List<LatLon>, maxMeters: Double = 35.0): RoadLabel? {
        if (points.isEmpty()) return null
        val votes = HashMap<String, Int>()
        var distanceSum = 0.0
        var matched = 0
        for (point in points) {
            val (best, distance) = segmentIndex.nearest(point, maxMeters) {
                Geo.distanceToSegment(point, it.a, it.b)
            } ?: continue
            votes[best.label] = (votes[best.label] ?: 0) + 1
            distanceSum += distance
            matched++
        }
        val top = votes.maxByOrNull { it.value } ?: return null
        return RoadLabel(top.key, if (matched == 0) 0.0 else distanceSum / matched)
    }

    fun junctionAt(id: Long): OsmJunction? = junctions.firstOrNull { it.id == id }

    private data class LabeledSegment(val a: LatLon, val b: LatLon, val label: String)
}

/**
 * Uniform grid in Web-Mercator metres. Good enough for "what is within 30 m of
 * this fix" and far cheaper than an R-tree to build for one trip.
 */
class GridIndex<T>(private val cellMeters: Double) {
    private val cells = HashMap<Long, MutableList<T>>()

    fun add(at: LatLon, item: T) {
        cells.getOrPut(key(at)) { ArrayList() } += item
    }

    fun addSegment(a: LatLon, b: LatLon, item: T) {
        val (x1, y1) = Mercator.toMeters(a)
        val (x2, y2) = Mercator.toMeters(b)
        val minCellX = floor(kotlin.math.min(x1, x2) / cellMeters).toInt()
        val maxCellX = floor(kotlin.math.max(x1, x2) / cellMeters).toInt()
        val minCellY = floor(kotlin.math.min(y1, y2) / cellMeters).toInt()
        val maxCellY = floor(kotlin.math.max(y1, y2) / cellMeters).toInt()
        for (cx in minCellX..maxCellX) {
            for (cy in minCellY..maxCellY) {
                cells.getOrPut(pack(cx, cy)) { ArrayList() } += item
            }
        }
    }

    fun around(point: LatLon, radiusMeters: Double): List<T> {
        val (x, y) = Mercator.toMeters(point)
        val reach = kotlin.math.ceil(radiusMeters / cellMeters).toInt()
        val cx = floor(x / cellMeters).toInt()
        val cy = floor(y / cellMeters).toInt()
        val out = ArrayList<T>()
        for (dx in -reach..reach) {
            for (dy in -reach..reach) {
                cells[pack(cx + dx, cy + dy)]?.let { out += it }
            }
        }
        return out
    }

    fun nearest(point: LatLon, maxMeters: Double, distanceOf: (T) -> Double): Pair<T, Double>? {
        val (x, y) = Mercator.toMeters(point)
        val reach = kotlin.math.ceil(maxMeters / cellMeters).toInt()
        val cx = floor(x / cellMeters).toInt()
        val cy = floor(y / cellMeters).toInt()
        var best: T? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -reach..reach) {
            for (dy in -reach..reach) {
                val bucket = cells[pack(cx + dx, cy + dy)] ?: continue
                for (candidate in bucket) {
                    val distance = distanceOf(candidate)
                    if (distance < bestDistance) {
                        best = candidate
                        bestDistance = distance
                    }
                }
            }
        }
        return best?.takeIf { bestDistance <= maxMeters }?.let { it to bestDistance }
    }

    private fun key(at: LatLon): Long {
        val (x, y) = Mercator.toMeters(at)
        return pack(floor(x / cellMeters).toInt(), floor(y / cellMeters).toInt())
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
}

object Mercator {
    private const val RADIUS = 6_378_137.0

    fun toMeters(at: LatLon): Pair<Double, Double> {
        val x = Math.toRadians(at.lon) * RADIUS
        val y = ln(tan(Math.PI / 4 + Math.toRadians(at.lat.coerceIn(-85.0, 85.0)) / 2)) * RADIUS
        return x to y
    }
}
