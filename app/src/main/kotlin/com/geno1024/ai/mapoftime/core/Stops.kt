package com.geno1024.ai.mapoftime.core

/**
 * How one leg's duration splits into movement and standing still.
 *
 * A leg still owns its whole duration, so the numbers add up:
 * `movingMs + stoppedMs == totalMs`. Waiting at a light is therefore part of
 * the segment's cost, but it can also be reported on its own.
 */
data class LegTiming(
    val totalMs: Long,
    val movingMs: Long,
    val stoppedMs: Long,
    /** Standstills of at least [MIN_STOP_MS]; a single GPS jitter is not one. */
    val stopCount: Int,
    val longestStopMs: Long,
) {
    val hasWait: Boolean get() = stoppedMs > 0

    companion object {
        /**
         * A standstill shorter than this is noise: GPS accuracy moves the fix
         * by a metre or two while the speed filter catches up. Real waiting at
         * a light lasts at least one full cycle.
         */
        const val MIN_STOP_MS = 5_000L

        val EMPTY = LegTiming(0, 0, 0, 0, 0)
    }
}

/**
 * One standstill inside a leg, with the place it happened. The position is what
 * turns "the user waited 24 s" into "the user waited 24 s at this light".
 */
data class StopSpan(val startMs: Long, val endMs: Long, val at: LatLon) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * How close a standstill must be to an OSM `highway=traffic_signals` node to be
 * called "waiting at a light". Wide enough for GPS error and for stopping a
 * few metres before the stop line; narrow enough not to catch the next block.
 */
const val SIGNAL_RADIUS_METERS = 30.0

/**
 * Finds the standstills inside one leg.
 *
 * Uses the same smoothed speeds the segmenter used to find long pauses, so the
 * two stages can never disagree about whether the user was standing still. A
 * standstill that reached [ModeProfile.stopAsPartOfLegMs] never gets here: the
 * segmenter already turned it into a pause and ended the leg around it.
 */
object StopCounter {
    /**
     * @param startMs beginning of the leg, i.e. when the trace crossed the
     *   junction; the first fix may be slightly later.
     * @param endMs end of the leg, used for a standstill that is still going on
     *   when the leg ends.
     */
    fun of(
        points: List<TrackPoint>,
        speeds: DoubleArray,
        slice: TrackSlice,
        profile: ModeProfile,
        startMs: Long,
        endMs: Long,
    ): LegTiming = timing(
        totalMs = (endMs - startMs).coerceAtLeast(0),
        stops = spans(points, speeds, slice, profile, endMs),
    )

    /** The standstills themselves, each anchored at the fix where it began. */
    fun spans(
        points: List<TrackPoint>,
        speeds: DoubleArray,
        slice: TrackSlice,
        profile: ModeProfile,
        endMs: Long,
    ): List<StopSpan> {
        if (slice.size < 2 || speeds.size < points.size) return emptyList()
        val out = ArrayList<StopSpan>()
        var runStart = -1

        fun close(end: Long) {
            val start = runStart
            if (start < 0) return
            // The trace stood still from the first still fix until movement
            // resumed at `end`; sampling error cancels out on average.
            val span = StopSpan(points[start].t, end, points[start].at)
            if (span.durationMs >= LegTiming.MIN_STOP_MS) out += span
            runStart = -1
        }

        for (i in slice.from until slice.to) {
            val still = i < speeds.size && speeds[i] < profile.stopSpeedMps
            if (still) {
                if (runStart < 0) runStart = i
            } else {
                close(points[i].t)
            }
        }
        // Still standing when the leg ends: the wait belongs to this leg.
        close(endMs.coerceAtLeast(points[slice.to - 1].t))
        return out
    }

    /** Turns the spans into the duration split the leg stores. */
    fun timing(totalMs: Long, stops: List<StopSpan>): LegTiming {
        if (totalMs == 0L) return LegTiming(0, 0, 0, 0, 0)
        val stopped = stops.sumOf { it.durationMs }.coerceIn(0L, totalMs)
        return LegTiming(
            totalMs = totalMs,
            movingMs = totalMs - stopped,
            stoppedMs = stopped,
            stopCount = stops.size,
            longestStopMs = stops.maxOfOrNull { it.durationMs } ?: 0L,
        )
    }
}
