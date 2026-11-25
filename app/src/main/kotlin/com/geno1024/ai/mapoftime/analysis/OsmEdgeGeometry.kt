package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.osm.OsmWay
import java.util.PriorityQueue

/**
 * Rebuilds an edge's road shape from the OSM ways already cached on the device.
 *
 * Recorded GPS follows the road too, but it jitters and can take a detour. When
 * both endpoints are OSM nodes and the cached ways connect them, the way
 * geometry is a cleaner description of the same road; recorded traces remain
 * the fallback for local `geo:` corners or missing tiles.
 */
class OsmPathIndex(ways: List<OsmWay>) {
    private data class Arm(val to: Long, val at: LatLon, val metres: Double)

    private val coordinates = HashMap<Long, LatLon>()
    private val arms = HashMap<Long, MutableList<Arm>>()

    init {
        for (way in ways) {
            val count = minOf(way.nodeIds.size, way.points.size)
            for (i in 0 until count) {
                coordinates.putIfAbsent(way.nodeIds[i], way.points[i])
            }
            for (i in 1 until count) {
                val a = way.nodeIds[i - 1]
                val b = way.nodeIds[i]
                val metres = Geo.distanceMeters(way.points[i - 1], way.points[i])
                // A road's shape is the same either way; a mismatch in `oneway`
                // is a tagging detail, not a reason to draw a different line.
                arms.getOrPut(a) { ArrayList() } += Arm(b, way.points[i], metres)
                arms.getOrPut(b) { ArrayList() } += Arm(a, way.points[i - 1], metres)
            }
        }
    }

    /** Shortest chain of way segments between two OSM nodes, or null. */
    fun path(fromNodeId: Long, toNodeId: Long): List<LatLon>? {
        if (fromNodeId == toNodeId) return null
        val start = coordinates[fromNodeId] ?: return null
        val best = HashMap<Long, Double>().apply { put(fromNodeId, 0.0) }
        val previous = HashMap<Long, Pair<Long, LatLon>>()
        val queue = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        queue += fromNodeId to 0.0

        while (queue.isNotEmpty()) {
            val (node, cost) = queue.remove()
            if (cost != best[node]) continue
            if (node == toNodeId) break
            for (arm in arms[node].orEmpty()) {
                val next = cost + arm.metres
                if (best[arm.to]?.let { next < it } != false) {
                    best[arm.to] = next
                    previous[arm.to] = node to arm.at
                    queue += arm.to to next
                }
            }
        }
        if (toNodeId !in best) return null

        val backwards = ArrayList<LatLon>()
        var cursor = toNodeId
        while (cursor != fromNodeId) {
            val (before, at) = previous[cursor] ?: return null
            backwards += at
            cursor = before
        }
        return buildList {
            add(start)
            for (i in backwards.indices.reversed()) add(backwards[i])
        }
    }
}

object OsmEdgeGeometry {
    /** Above this the "shortest" path wandered far from what was travelled. */
    private const val MAX_LENGTH_FACTOR = 2.5
    private const val LENGTH_SLACK_M = 60.0

    fun shape(index: OsmPathIndex, edge: Edge): List<LatLon>? {
        val fromId = PointRef.parse(edge.fromKey)?.takeIf { it.kind == PointRef.OSM }?.nodeId
            ?: return null
        val toId = PointRef.parse(edge.toKey)?.takeIf { it.kind == PointRef.OSM }?.nodeId
            ?: return null
        val path = index.path(fromId, toId)?.takeIf { it.size >= 2 } ?: return null
        if (Geo.pathLengthMeters(path) > edge.lengthM * MAX_LENGTH_FACTOR + LENGTH_SLACK_M) {
            return null
        }
        return buildList {
            add(edge.from)
            addAll(path.subList(1, path.size - 1))
            add(edge.to)
        }
    }
}
