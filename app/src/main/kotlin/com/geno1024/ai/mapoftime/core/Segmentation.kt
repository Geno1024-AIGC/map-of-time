package com.geno1024.ai.mapoftime.core

import kotlin.math.abs

/** Half-open range `[from, to)` over a cleaned point list. */
data class TrackSlice(val from: Int, val to: Int) {
    val size: Int get() = to - from

    companion object {
        val EMPTY = TrackSlice(0, 0)
    }
}

data class SegmentationResult(
    /** Points left after the validity filters, indices below refer to this list. */
    val points: List<TrackPoint>,
    /** Travel legs that make up the trip, in order. */
    val legs: List<TrackSlice>,
    /** Standstills that were excluded from both neighbouring legs. */
    val pauses: List<TrackSlice>,
    /** How many raw fixes the accuracy/speed filters threw away. */
    val droppedPoints: Int,
)

data class CleanedTrace(
    val points: List<TrackPoint>,
    /** Indices that must start a new leg: an impossible jump happened before them. */
    val breaks: Set<Int>,
    val droppedPoints: Int,
)

/**
 * Turns one raw GPS trace into legs.
 *
 * Two kinds of boundary exist and they do different jobs:
 *
 * - **Breaks** (long standstill, GPS gap) end a leg and are excluded from it.
 *   A short stop stays inside the leg, because queueing at a light is part of
 *   what that road costs you.
 * - **Turns** and **manual marks** only split a leg in two; the time is still
 *   travel time, it just belongs to a different road.
 *
 * Road junctions, when OSM data is available, split legs further. This stage is
 * what works without any map data at all.
 */
object Segmenter {
    /** A jump bigger than this is not travel, it is a bad fix. */
    private const val MAX_GAP_MS = 90_000L

    fun segment(
        raw: List<TrackPoint>,
        profile: ModeProfile,
        manualMarks: List<Long> = emptyList(),
    ): SegmentationResult {
        val cleaned = clean(raw, profile)
        val points = cleaned.points
        if (points.size < 2) {
            return SegmentationResult(points, emptyList(), emptyList(), cleaned.droppedPoints)
        }

        val speeds = speedsOf(points)
        val pauses = findPauses(points, speeds, profile)

        val cuts = sortedSetOf(0)
        for (pause in pauses) {
            cuts += pause.from
            cuts += pause.to
        }
        cuts += cleaned.breaks
        cuts += gapCuts(points)
        cuts += turnCuts(points, speeds, passages = pauses, profile)
        cuts += markCuts(points, manualMarks)
        cuts.removeAll { it < 0 || it >= points.size }

        val boundaries = (cuts + points.size).sorted()
        val units = boundaries.zipWithNext()
            .map { (a, b) -> Unit(TrackSlice(a, b), pause = isPause(TrackSlice(a, b), pauses)) }
            .toMutableList()

        absorbShortUnits(units, points, profile)

        return SegmentationResult(
            points = points,
            legs = units.filterNot { it.pause }.map { it.slice },
            pauses = pauses,
            droppedPoints = cleaned.droppedPoints,
        )
    }

    private data class Unit(val slice: TrackSlice, val pause: Boolean)

    /**
     * Short slices join the leg next to them, never across a pause: a 4 m
     * fragment is GPS noise on the road, but a fragment on the other side of a
     * coffee break belongs to a different journey.
     */
    private fun absorbShortUnits(units: MutableList<Unit>, points: List<TrackPoint>, profile: ModeProfile) {
        var changed = true
        while (changed) {
            changed = false
            for (i in units.indices) {
                val unit = units[i]
                if (unit.pause || !isTooShort(unit.slice, points, profile)) continue
                val previous = units.getOrNull(i - 1)
                val next = units.getOrNull(i + 1)
                when {
                    previous != null && !previous.pause && previous.slice.to == unit.slice.from -> {
                        units[i - 1] = Unit(previous.slice.copy(to = unit.slice.to), pause = false)
                        units.removeAt(i)
                        changed = true
                    }
                    next != null && !next.pause && next.slice.from == unit.slice.to -> {
                        units[i + 1] = Unit(next.slice.copy(from = unit.slice.from), pause = false)
                        units.removeAt(i)
                        changed = true
                    }
                    else -> {
                        units.removeAt(i)
                        changed = true
                    }
                }
                if (changed) break
            }
        }
    }

    /** Drops fixes that cannot be true: poor accuracy, time going backwards, car-speed walking. */
    fun clean(raw: List<TrackPoint>, profile: ModeProfile): CleanedTrace {
        val out = ArrayList<TrackPoint>(raw.size)
        val breaks = mutableSetOf<Int>()
        var dropped = 0
        for (point in raw.sortedBy { it.t }) {
            if (!point.at.isValid) {
                dropped++
                continue
            }
            val last = out.lastOrNull()
            if (last == null) {
                out += point
                continue
            }
            val dt = point.t - last.t
            if (dt <= 0) {
                dropped++
                continue
            }
            if (point.accuracy > maxAccuracy(profile)) {
                dropped++
                continue
            }
            val meters = Geo.distanceMeters(last.at, point.at)
            // A hop farther than the mode can travel is a discontinuity, not a
            // fix to throw away: the trace resumes at the new place, with both
            // sides kept and a hard leg boundary between them.
            if (meters > 25.0 && meters / (dt / 1000.0) > profile.maxSpeedMps) {
                breaks += out.size
            }
            out += point
        }
        return CleanedTrace(out, breaks, dropped)
    }

    private fun maxAccuracy(profile: ModeProfile): Double =
        if (profile.minLegMeters < 25.0) 40.0 else 60.0

    /**
     * Median of the previous, current and next sample speed. One pair of fixes
     * is far too noisy to call a standstill on; the median rejects the single
     * bad spike on either side. Exposed because the wait-time counter must use
     * exactly the speeds the segmenter used.
     */
    fun speedsOf(points: List<TrackPoint>): DoubleArray {
        if (points.isEmpty()) return DoubleArray(0)
        val instant = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val dt = (points[i].t - points[i - 1].t) / 1000.0
            instant[i] = if (dt > 0) Geo.distanceMeters(points[i - 1].at, points[i].at) / dt else 0.0
        }
        instant[0] = instant.getOrElse(1) { 0.0 }
        val out = DoubleArray(points.size)
        for (i in points.indices) {
            val window = instant.copyOfRange((i - 1).coerceAtLeast(0), (i + 2).coerceAtMost(points.size))
            out[i] = window.sorted()[window.size / 2]
        }
        return out
    }

    /** Standstills long enough to be a break rather than part of the journey. */
    private fun findPauses(
        points: List<TrackPoint>,
        speeds: DoubleArray,
        profile: ModeProfile,
    ): List<TrackSlice> {
        val pauses = mutableListOf<TrackSlice>()
        var start = -1
        for (i in points.indices) {
            val still = speeds[i] < profile.stopSpeedMps
            if (still && start < 0) start = i
            if (!still && start >= 0) {
                addPause(pauses, points, start, i, profile)
                start = -1
            }
        }
        if (start >= 0) addPause(pauses, points, start, points.size, profile)
        return pauses
    }

    private fun addPause(
        into: MutableList<TrackSlice>,
        points: List<TrackPoint>,
        from: Int,
        to: Int,
        profile: ModeProfile,
    ) {
        if (to - from < 2) return
        val duration = points[to - 1].t - points[from].t
        if (duration < profile.stopAsPartOfLegMs) return
        into += TrackSlice(from, to)
    }

    /** A hole in the trace means the journey may have gone anywhere; never bridge it. */
    private fun gapCuts(points: List<TrackPoint>): List<Int> =
        (1 until points.size).filter { points[it].t - points[it - 1].t > MAX_GAP_MS }

    private fun turnCuts(
        points: List<TrackPoint>,
        speeds: DoubleArray,
        passages: List<TrackSlice>,
        profile: ModeProfile,
    ): List<Int> {
        val cuts = mutableListOf<Int>()
        var lastCut = 0
        var i = 0
        while (i < points.size) {
            if (isInside(passages, i)) {
                i++
                continue
            }
            if (speeds[i] < profile.stopSpeedMps) {
                i++
                continue
            }
            val before = headingAt(points, i, -1)
            val after = headingAt(points, i, +1)
            if (before != null && after != null) {
                val turn = Geo.angleDifference(before, after)
                val farEnough = Geo.distanceMeters(points[lastCut].at, points[i].at) >= profile.minLegForSplitMeters
                if (turn >= profile.turnDegrees && farEnough) {
                    cuts += i
                    lastCut = i
                }
            }
            i++
        }
        return cuts
    }

    /**
     * Heading of the trace a few metres before (`direction = -1`) or after
     * (`+1`) [index]. A single GPS pair is far too noisy to call a turn on.
     */
    private fun headingAt(points: List<TrackPoint>, index: Int, direction: Int): Double? {
        val anchor = points[index]
        var cursor = index
        var travelled = 0.0
        var last = anchor
        while (travelled < 8.0) {
            val next = cursor + direction
            if (next !in points.indices) break
            travelled += Geo.distanceMeters(points[cursor].at, points[next].at)
            cursor = next
            last = points[cursor]
        }
        if (travelled < 5.0) return null
        if (cursor == index) return null
        return if (direction < 0) {
            Geo.bearingDegrees(last.at, anchor.at)
        } else {
            Geo.bearingDegrees(anchor.at, last.at)
        }
    }

    private fun markCuts(points: List<TrackPoint>, marks: List<Long>): List<Int> =
        marks.mapNotNull { mark -> nearestIndex(points, mark) }

    fun nearestIndex(points: List<TrackPoint>, time: Long): Int? {
        if (points.isEmpty()) return null
        var best = 0
        var bestDelta = Long.MAX_VALUE
        for (i in points.indices) {
            val delta = abs(points[i].t - time)
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return if (bestDelta > 5_000) null else best
    }

    private fun isInside(slices: List<TrackSlice>, index: Int): Boolean =
        slices.any { index >= it.from && index < it.to }

    private fun isPause(slice: TrackSlice, pauses: List<TrackSlice>): Boolean =
        pauses.any { slice.from >= it.from && slice.to <= it.to }

    private fun isTooShort(slice: TrackSlice, points: List<TrackPoint>, profile: ModeProfile): Boolean {
        if (slice.size < 2) return true
        val meters = Geo.pathLengthMeters(
            points.subList(slice.from, slice.to).map { it.at },
            minStep = 0.5,
        )
        val duration = points[slice.to - 1].t - points[slice.from].t
        return meters < profile.minLegMeters || duration < profile.minLegMs
    }
}
