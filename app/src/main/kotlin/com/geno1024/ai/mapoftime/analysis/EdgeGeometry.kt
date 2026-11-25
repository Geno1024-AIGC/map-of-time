package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.EdgeShapeRow
import kotlin.math.ceil

/**
 * Turns a few recorded traversals into the line the network map draws for an
 * edge.
 *
 * One leg can be an outlier — a wrong turn, a GPS jump, a detour around road
 * works. So every valid leg is normalised to run from the edge's `from` to its
 * `to`, and the one closest to the others (the medoid) wins. Douglas–Peucker
 * then keeps its bends without keeping every jittery fix.
 *
 * The network's nodes are identities, not coordinates, but the drawn line still
 * has to land on the node dots: traces that do not reach both ends — partial
 * legs, truncated recordings, GPS that jumped away — are rejected.
 */
object EdgeGeometry {
    /** How far a leg's end may sit from its edge's end and still describe it. */
    private const val END_TOLERANCE_M = 40.0

    /** Points used to compare shapes without letting fix density decide. */
    private const val MEDOID_SAMPLES = 16

    /** Sub-metre jitter is not a corner. */
    private const val RDP_TOLERANCE_M = 4.0

    /** A single edge never needs more points than this on a phone screen. */
    private const val MAX_POINTS = 120

    fun displayShape(
        edge: Edge,
        candidates: List<EdgeShapeRow>,
    ): List<LatLon> {
        val normalised = candidates.mapNotNull { normalise(edge, it) }
        if (normalised.isEmpty()) return emptyList()

        val chosen = if (normalised.size == 1) {
            normalised.first()
        } else {
            val sampled = normalised.map { resample(it, MEDOID_SAMPLES) }
            var best = 0
            var bestScore = Double.MAX_VALUE
            sampled.forEachIndexed { index, shape ->
                val score = averageDistance(shape, sampled.filterIndexed { other, _ -> other != index })
                if (score < bestScore) {
                    bestScore = score
                    best = index
                }
            }
            normalised[best]
        }
        return cap(simplify(chosen, RDP_TOLERANCE_M))
    }

    /** Orients one leg from the edge's start and snaps both ends to the nodes. */
    private fun normalise(edge: Edge, candidate: EdgeShapeRow): List<LatLon>? {
        if (candidate.partial) return null
        val points = Polyline.decode(candidate.polyline)
        if (points.size < 2) return null

        // Direction is not always trustworthy (imported legs can carry 0), so
        // orient by which way the trace actually runs between the endpoints.
        val forward = if (endsDistance(points, edge) <= endsDistance(points.asReversed(), edge)) {
            points
        } else {
            points.asReversed()
        }
        if (Geo.distanceMeters(forward.first(), edge.from) > END_TOLERANCE_M ||
            Geo.distanceMeters(forward.last(), edge.to) > END_TOLERANCE_M
        ) {
            return null
        }
        return buildList {
            add(edge.from)
            addAll(forward.subList(1, forward.size - 1))
            add(edge.to)
        }
    }

    private fun endsDistance(points: List<LatLon>, edge: Edge): Double =
        Geo.distanceMeters(points.first(), edge.from) + Geo.distanceMeters(points.last(), edge.to)

    /** Equal arc-length samples, so a chatty recorder does not outvote a terse one. */
    private fun resample(points: List<LatLon>, count: Int): List<LatLon> {
        if (points.size <= count) return points
        val total = Geo.pathLengthMeters(points)
        if (total <= 0.0) return listOf(points.first())
        val step = total / (count - 1)
        val out = ArrayList<LatLon>(count)
        out += points.first()
        var walked = 0.0
        var next = step
        for (i in 1 until points.size) {
            val segment = Geo.distanceMeters(points[i - 1], points[i])
            if (segment <= 0.0) continue
            while (next <= walked + segment && out.size < count) {
                out += Geo.interpolate(points[i - 1], points[i], (next - walked) / segment)
                next += step
            }
            walked += segment
            if (out.size >= count) break
        }
        while (out.size < count) out += points.last()
        return out
    }

    private fun averageDistance(shape: List<LatLon>, others: List<List<LatLon>>): Double {
        if (others.isEmpty() || shape.isEmpty()) return 0.0
        return others.sumOf { other ->
            (0 until minOf(shape.size, other.size)).sumOf { i ->
                Geo.distanceMeters(shape[i], other[i])
            }.div(minOf(shape.size, other.size).coerceAtLeast(1))
        } / others.size
    }

    /** Keeps corners above [toleranceM] and throws away near-collinear fixes. */
    private fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size).apply {
            this[0] = true
            this[lastIndex] = true
        }
        fun run(from: Int, to: Int) {
            if (to <= from + 1) return
            var farthest = -1.0
            var index = -1
            for (i in from + 1 until to) {
                val distance = Geo.distanceToSegment(points[i], points[from], points[to])
                if (distance > farthest) {
                    farthest = distance
                    index = i
                }
            }
            if (index >= 0 && farthest > toleranceM) {
                keep[index] = true
                run(from, index)
                run(index, to)
            }
        }
        run(0, points.lastIndex)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun cap(points: List<LatLon>): List<LatLon> {
        if (points.size <= MAX_POINTS) return points
        val stride = ceil(points.size / MAX_POINTS.toDouble()).toInt()
        val out = ArrayList<LatLon>(MAX_POINTS + 1)
        for (i in points.indices step stride) out += points[i]
        if (out.last() != points.last()) out += points.last()
        return out
    }
}
