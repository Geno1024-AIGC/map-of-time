package com.geno1024.ai.mapoftime.track

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon

/**
 * Running distance of a live GPS trace.
 *
 * The anchor is the last fix that actually changed the total, not the last fix
 * received. That distinction matters: at walking speed a fix arrives every
 * second about 1.4 m after the previous one. Measuring each of those steps
 * against a 1.5 m minimum and then moving the anchor anyway would discard
 * nearly every step and report a small fraction of the walk.
 */
class DistanceAccumulator(
    private val minStepMeters: Double = MIN_STEP_METERS,
    private val maxStepMeters: Double = MAX_STEP_METERS,
) {
    private var anchor: LatLon? = null

    var meters: Double = 0.0
        private set

    fun reset() {
        anchor = null
        meters = 0.0
    }

    /**
     * @param trustworthy false when the fix accuracy is too poor to measure
     *   with. Such a fix does not move the anchor, so the next good fix still
     *   covers whatever was walked in between.
     */
    fun add(at: LatLon, trustworthy: Boolean = true) {
        val previous = anchor
        if (previous == null) {
            anchor = at
            return
        }
        if (!trustworthy) return

        val step = Geo.distanceMeters(previous, at)
        when {
            // A hop this large is a bad fix, not travel; resume from here
            // instead of adding a straight line across the map.
            step > maxStepMeters -> anchor = at
            // Below the threshold the anchor stays put, so several small steps
            // accumulate into the distance that was really walked.
            step >= minStepMeters -> {
                meters += step
                anchor = at
            }
        }
    }

    companion object {
        /** One walking stride at 1 Hz; anything shorter is jitter. */
        const val MIN_STEP_METERS = 1.5

        /** No mode moves 200 m between two fixes a second apart. */
        const val MAX_STEP_METERS = 200.0
    }
}
