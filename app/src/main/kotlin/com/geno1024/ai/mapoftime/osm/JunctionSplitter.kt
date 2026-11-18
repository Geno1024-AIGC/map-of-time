package com.geno1024.ai.mapoftime.osm

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TrackSlice

/**
 * One end of a piece. A junction id means "this end is a real intersection in
 * OSM"; `null` means the trip started or ended in the middle of a road, which
 * makes the piece partial and unfit for statistics.
 */
data class PieceAnchor(val junctionId: Long?, val at: LatLon) {
    val isJunction: Boolean get() = junctionId != null
}

data class MatchedPiece(
    val slice: TrackSlice,
    val start: PieceAnchor,
    val end: PieceAnchor,
    val label: String?,
) {
    val boundedByJunctions: Boolean get() = start.isJunction && end.isJunction
}

/**
 * Cuts one leg at every OSM intersection it passes, so that "a road between two
 * crossings" becomes the unit of measurement no matter how long the walk was.
 *
 * A junction only counts when it is genuinely approached and left: a crossing
 * on a parallel street sits at a constant distance and is not a local minimum.
 * Without that check every walk beside a side street would invent boundaries.
 */
object JunctionSplitter {
    const val DEFAULT_SNAP_METERS = 30.0
    private const val MIN_SPACING_METERS = 12.0
    private const val LOCAL_WINDOW_METERS = 25.0

    fun split(
        points: List<TrackPoint>,
        leg: TrackSlice,
        graph: RoadGraph?,
        snapMeters: Double = DEFAULT_SNAP_METERS,
        minPieceMeters: Double = 8.0,
    ): List<MatchedPiece> {
        if (leg.size < 2) return emptyList()
        if (graph == null) {
            return listOf(
                MatchedPiece(
                    slice = leg,
                    start = PieceAnchor(null, points[leg.from].at),
                    end = PieceAnchor(null, points[leg.to - 1].at),
                    label = null,
                ),
            )
        }

        val boundaries = mutableListOf<Boundary>()
        boundaries += Boundary(leg.from, points[leg.from].at, junctionNear(points[leg.from].at, graph, snapMeters))
        for (index in hits(points, leg, graph, crossingMeters(snapMeters))) {
            val junction = graph.nearestJunction(points[index].at, snapMeters) ?: continue
            val previous = boundaries.last()
            if (previous.junction?.id == junction.id) continue
            if (Geo.distanceMeters(previous.at, points[index].at) < MIN_SPACING_METERS) continue
            boundaries += Boundary(index, points[index].at, junction)
        }
        boundaries += Boundary(leg.to - 1, points[leg.to - 1].at, junctionNear(points[leg.to - 1].at, graph, snapMeters))

        val pieces = mutableListOf<MatchedPiece>()
        for (i in 0 until boundaries.size - 1) {
            val from = boundaries[i]
            val to = boundaries[i + 1]
            if (to.index <= from.index) continue
            val slice = TrackSlice(from.index, to.index + 1)
            pieces += MatchedPiece(
                slice = slice,
                start = PieceAnchor(from.junction?.id, from.at),
                end = PieceAnchor(to.junction?.id, to.at),
                label = graph.dominantLabel(points.subList(slice.from, slice.to).map { it.at })?.name,
            )
        }

        return mergeShortPieces(pieces, points, minPieceMeters)
    }

    /** Indices where the trace comes closest to an intersection. */
    private fun hits(        points: List<TrackPoint>,
        leg: TrackSlice,
        graph: RoadGraph,
        snapMeters: Double,
    ): List<Int> {
        val distance = arrayOfNulls<Double>(leg.size)
        val junction = arrayOfNulls<OsmJunction>(leg.size)
        for (offset in 0 until leg.size) {
            val index = leg.from + offset
            val candidate = graph.nearestJunction(points[index].at, snapMeters)
            if (candidate != null) {
                distance[offset] = Geo.distanceMeters(points[index].at, candidate.at)
                junction[offset] = candidate
            }
        }

        val result = mutableListOf<Int>()
        for (offset in 0 until leg.size) {
            val here = distance[offset] ?: continue
            if (!isLocalMinimum(points, leg, distance, offset, here)) continue
            result += leg.from + offset
        }
        return result
    }

    private fun isLocalMinimum(
        points: List<TrackPoint>,
        leg: TrackSlice,
        distance: Array<Double?>,
        offset: Int,
        here: Double,
    ): Boolean {
        val center = points[leg.from + offset].at
        for (step in -1..1 step 2) {
            var cursor = offset + step
            while (cursor in 0 until leg.size &&
                Geo.distanceMeters(center, points[leg.from + cursor].at) <= LOCAL_WINDOW_METERS
            ) {
                val other = distance[cursor]
                // Strictly closer than everything around, so a plateau of equal
                // distances (a parallel road) cannot qualify at all.
                if (other != null && other <= here) return false
                cursor += step
            }
        }
        return true
    }

    private fun junctionNear(point: LatLon, graph: RoadGraph, snapMeters: Double): OsmJunction? =
        graph.nearestJunction(point, snapMeters)

    /**
     * A leg may be *snapped* to a crossing 30 m away (a wide junction, a
     * sidewalk offset), but it only counts as *passing through* one when the
     * trace gets really close. Otherwise every walk beside a side street would
     * be cut by it.
     */
    private fun crossingMeters(snapMeters: Double): Double = minOf(snapMeters, 22.0)

    /**
     * A sub-8 m piece is GPS noise around one crossing, not a road. It is
     * absorbed by the neighbouring piece so its time is not lost.
     */
    private fun mergeShortPieces(
        pieces: MutableList<MatchedPiece>,
        points: List<TrackPoint>,
        minPieceMeters: Double,
    ): List<MatchedPiece> {
        var merged = true
        while (merged && pieces.size > 1) {
            merged = false
            for (i in pieces.indices) {
                val piece = pieces[i]
                val meters = Geo.pathLengthMeters(
                    points.subList(piece.slice.from, piece.slice.to).map { it.at },
                    minStep = 0.5,
                )
                if (meters >= minPieceMeters) continue
                if (i > 0) {
                    val previous = pieces[i - 1]
                    pieces[i - 1] = previous.copy(
                        slice = TrackSlice(previous.slice.from, piece.slice.to),
                        end = piece.end,
                    )
                } else {
                    val next = pieces[i + 1]
                    pieces[i + 1] = next.copy(
                        slice = TrackSlice(piece.slice.from, next.slice.to),
                        start = piece.start,
                    )
                }
                pieces.removeAt(i)
                merged = true
                break
            }
        }
        return pieces
    }

    private data class Boundary(val index: Int, val at: LatLon, val junction: OsmJunction?)
}
