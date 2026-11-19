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
    ): LegTiming {
        val total = (endMs - startMs).coerceAtLeast(0)
        if (total == 0L || slice.size < 2 || speeds.size < points.size) {
            return LegTiming(total, total, 0, 0, 0)
        }

        var stopped = 0L
        var count = 0
        var longest = 0L
        var runStart = -1

        fun close(end: Long) {
            val start = runStart
            if (start < 0) return
            // The trace stood still from the first still fix until movement
            // resumed at `end`; sampling error cancels out on average.
            val duration = end - points[start].t
            if (duration >= LegTiming.MIN_STOP_MS) {
                stopped += duration
                count++
                if (duration > longest) longest = duration
            }
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

        stopped = stopped.coerceIn(0L, total)
        return LegTiming(
            totalMs = total,
            movingMs = total - stopped,
            stoppedMs = stopped,
            stopCount = count,
            longestStopMs = longest,
        )
    }
}
