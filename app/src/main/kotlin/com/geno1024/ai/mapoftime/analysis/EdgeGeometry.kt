package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.data.Edge

/**
 * Turns one recorded traversal into the line the network map draws for an edge.
 *
 * The network's nodes are identities, not coordinates, but the drawn line still
 * has to land on the node dots. A leg starts and ends near the edge's endpoints
 * (which may be a junction a few metres wide), so the shape keeps the recorded
 * bends and snaps both ends to the edge's exact endpoint coordinates. Traces
 * that do not reach both ends — partial legs, truncated recordings, GPS that
 * jumped away — are rejected; the caller falls back to a straight line.
 */
object EdgeGeometry {
    /** How far a leg's end may sit from its edge's end and still describe it. */
    private const val END_TOLERANCE_M = 40.0

    /** GPS fixes closer than this are jitter, not another corner. */
    private const val MIN_STEP_M = 5.0

    fun displayShape(
        edge: Edge,
        polyline: String,
        partial: Boolean,
    ): List<LatLon> {
        if (partial) return emptyList()
        val points = Polyline.decode(polyline)
        if (points.size < 2) return emptyList()

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
            return emptyList()
        }

        val inner = ArrayList<LatLon>()
        var anchor = edge.from
        for (point in forward.subList(1, forward.size - 1)) {
            if (Geo.distanceMeters(anchor, point) >= MIN_STEP_M) {
                inner += point
                anchor = point
            }
        }
        return buildList {
            add(edge.from)
            addAll(inner)
            add(edge.to)
        }
    }

    private fun endsDistance(points: List<LatLon>, edge: Edge): Double =
        Geo.distanceMeters(points.first(), edge.from) + Geo.distanceMeters(points.last(), edge.to)
}
