package com.geno1024.ai.mapoftime.agg

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TripMode
import kotlin.math.cos
import kotlin.math.floor

/**
 * The alternative app's point model.
 *
 * Nothing here touches the classic identity layer (`osm:` / `geo:` keys,
 * edges, legs, samples, names). Points are derived from the raw GPS fixes of
 * every trip: places that were actually visited enough to matter. Ids are
 * local to one aggregation run and are handed out in a stable order so the
 * same data produces the same network.
 */
data class AggPoint(
    val id: Int,
    val at: LatLon,
    /** GPS fixes that landed here. */
    val visits: Int,
    /** Distinct trips that landed here. */
    val trips: Int,
    val radiusM: Double,
)

/** One undirected connection between two aggregated points, for one mode. */
data class AggEdge(
    val fromId: Int,
    val toId: Int,
    val mode: TripMode,
    /** Median duration of all observed crossings. */
    val typicalMs: Long,
    val samples: Int,
    val distanceM: Double,
)

data class AggTrace(
    val tripId: Long,
    val mode: TripMode,
    val fixes: List<TrackPoint>,
)

data class AggNetwork(
    val points: List<AggPoint>,
    val edges: List<AggEdge>,
    val trips: Int,
    /** Fixes left after cleaning; the UI shows this next to the point count. */
    val fixes: Int,
    /** Trips with fewer than two matched points: they contribute no edges. */
    val unmatchedTrips: Int,
)

private data class SampleKey(val low: Int, val high: Int, val mode: TripMode)
private data class Visit(val pointId: Int, val firstT: Long, val lastT: Long)
private data class Cluster(val at: LatLon, val visits: Int, val trips: Int, val radiusM: Double)

/**
 * Spatial aggregation of raw tracks. Deliberately a pure function: the whole
 * network is recomputed from the same trip data whenever that data changes,
 * so there is no second persistent identity to migrate.
 */
object Aggregator {
    /** Cell size of the first pass, in metres. */
    private const val CELL_M = 30.0
    /** Neighbouring cells whose centres are this close are the same place. */
    private const val MERGE_M = 45.0
    /** How far a fix can be from a point and still count as visiting it. */
    private const val MATCH_M = 70.0
    /** A single visit is noise; a place is somewhere fixes pile up. */
    private const val MIN_FIXES = 6
    private const val MAX_ACCURACY_M = 120.0
    /** Longer gaps are a different day's business, not one crossing. */
    private const val MAX_GAP_MS = 30 * 60 * 1000L

    fun aggregate(traces: List<AggTrace>): AggNetwork {
        val cleaned = traces.map { trace ->
            trace.copy(fixes = trace.fixes.filter { it.at.isValid && it.accuracy <= MAX_ACCURACY_M })
        }
        val origin = cleaned.firstNotNullOfOrNull { it.fixes.firstOrNull()?.at }
            ?: return AggNetwork(emptyList(), emptyList(), traces.size, 0, traces.size)
        val lonScale = 111_320.0 * cos(Math.toRadians(origin.lat))

        fun cell(at: LatLon): Pair<Int, Int> {
            val x = (at.lon - origin.lon) * lonScale / CELL_M
            val y = (at.lat - origin.lat) * 110_540.0 / CELL_M
            return floor(x).toInt() to floor(y).toInt()
        }

        // Bucket fixes by cell, keeping trip ids for "visited on N trips".
        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<LatLon, Long>>>()
        for (trace in cleaned) {
            for (fix in trace.fixes) {
                buckets.getOrPut(cell(fix.at)) { mutableListOf() } += fix.at to trace.tripId
            }
        }

        // Union adjacent cells whose centres are close: a place straddles the
        // grid line far too often to trust the grid itself as identity.
        val parent = HashMap<Pair<Int, Int>, Pair<Int, Int>>().apply { buckets.keys.forEach { put(it, it) } }
        fun find(key: Pair<Int, Int>): Pair<Int, Int> {
            var root = key
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var cursor = key
            while (parent.getValue(cursor) != cursor) {
                val next = parent.getValue(cursor)
                parent[cursor] = root
                cursor = next
            }
            return root
        }
        fun union(a: Pair<Int, Int>, b: Pair<Int, Int>) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for ((key, fixes) in buckets) {
            val lat = fixes.sumOf { it.first.lat } / fixes.size
            val lon = fixes.sumOf { it.first.lon } / fixes.size
            val (kx, ky) = key
            for (dx in -1..1) for (dy in -1..1) {
                val neighbour = (kx + dx) to (ky + dy)
                val other = buckets[neighbour] ?: continue
                val otherLat = other.sumOf { it.first.lat } / other.size
                val otherLon = other.sumOf { it.first.lon } / other.size
                if (Geo.distanceMeters(LatLon(lat, lon), LatLon(otherLat, otherLon)) <= MERGE_M) {
                    union(key, neighbour)
                }
            }
        }
        // find() returns a cell key, but the union tree's root may change as
        // more unions arrive; re-canonicalise every cell before grouping.
        val roots = buckets.keys.associateWith { find(it) }
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<LatLon, Long>>>()
        for ((key, fixes) in buckets) {
            groups.getOrPut(roots.getValue(key)) { mutableListOf() }.addAll(fixes)
        }

        // Stable ids: most-travelled places first, then coordinates as a
        // tie-breaker. Recomputing the same data gives the same ids.
        val points = groups.values.map { fixes ->
            val centre = LatLon(
                fixes.sumOf { it.first.lat } / fixes.size,
                fixes.sumOf { it.first.lon } / fixes.size,
            )
            Cluster(
                at = centre,
                visits = fixes.size,
                trips = fixes.map { it.second }.toSet().size,
                radiusM = fixes.maxOf { Geo.distanceMeters(centre, it.first) },
            )
        }.filter { it.visits >= MIN_FIXES }
            .sortedWith(compareByDescending<Cluster> { it.trips }
                .thenByDescending { it.visits }
                .thenBy { it.at.lat }.thenBy { it.at.lon })
            .mapIndexed { index, cluster ->
                AggPoint(
                    id = index + 1,
                    at = cluster.at,
                    visits = cluster.visits,
                    trips = cluster.trips,
                    radiusM = cluster.radiusM,
                )
            }

        // Cell index for matching fixes to points without O(points) per fix.
        val matchCell = HashMap<Pair<Int, Int>, MutableList<AggPoint>>()
        for (point in points) {
            matchCell.getOrPut(cell(point.at)) { mutableListOf() } += point
        }
        fun nearest(at: LatLon): AggPoint? {
            val (kx, ky) = cell(at)
            var best: AggPoint? = null
            var bestM = MATCH_M
            for (dx in -1..1) for (dy in -1..1) {
                for (point in matchCell[(kx + dx) to (ky + dy)].orEmpty()) {
                    val metres = Geo.distanceMeters(at, point.at)
                    if (metres <= bestM) {
                        best = point
                        bestM = metres
                    }
                }
            }
            return best
        }

        val samples = LinkedHashMap<SampleKey, MutableList<Long>>()
        var unmatchedTrips = 0
        for (trace in cleaned) {
            val visits = mutableListOf<Visit>()
            for (fix in trace.fixes) {
                val point = nearest(fix.at) ?: continue
                val last = visits.lastOrNull()
                if (last?.pointId == point.id) {
                    visits[visits.lastIndex] = last.copy(lastT = fix.t)
                } else {
                    visits += Visit(point.id, fix.t, fix.t)
                }
            }
            if (visits.size < 2) {
                unmatchedTrips++
                continue
            }
            for (i in 1 until visits.size) {
                val from = visits[i - 1]
                val to = visits[i]
                val duration = to.firstT - from.lastT
                if (duration < 0 || duration > MAX_GAP_MS) continue
                val key = SampleKey(
                    minOf(from.pointId, to.pointId),
                    maxOf(from.pointId, to.pointId),
                    trace.mode,
                )
                samples.getOrPut(key) { mutableListOf() } += duration
            }
        }

        val byId = points.associateBy { it.id }
        val edges = samples.map { (key, times) ->
            val sorted = times.sorted()
            AggEdge(
                fromId = key.low,
                toId = key.high,
                mode = key.mode,
                typicalMs = sorted[sorted.size / 2],
                samples = times.size,
                distanceM = Geo.distanceMeters(byId.getValue(key.low).at, byId.getValue(key.high).at),
            )
        }.sortedWith(compareByDescending<AggEdge> { it.samples }.thenBy { it.fromId }.thenBy { it.toId })

        return AggNetwork(
            points = points,
            edges = edges,
            trips = traces.size,
            fixes = cleaned.sumOf { it.fixes.size },
            unmatchedTrips = unmatchedTrips,
        )
    }
}
