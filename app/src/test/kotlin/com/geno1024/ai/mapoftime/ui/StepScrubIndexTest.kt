package com.geno1024.ai.mapoftime.ui

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class StepScrubIndexTest {

    @Test
    fun movingFixesStepOneAtATime() {
        // ~1.1 m/s, far above the standstill threshold.
        val points = (0..5).map { i -> TrackPoint(i * 1000L, LatLon(0.0, i * 0.00001)) }

        assertEquals(1, stepScrubIndex(points, 0, +1))
        assertEquals(4, stepScrubIndex(points, 5, -1))
    }

    @Test
    fun oneTapCrossesAStandstillCluster() {
        // Ten noisy fixes in one spot, then the walk continues at ~1.1 m/s.
        val points = buildList {
            add(TrackPoint(0L, LatLon(0.0, 0.0)))
            repeat(10) { i ->
                add(TrackPoint((i + 1) * 1000L, LatLon(0.0, 0.000001 * (i % 2))))
            }
            add(TrackPoint(11_000L, LatLon(0.0, 0.00002)))
            add(TrackPoint(12_000L, LatLon(0.0, 0.00003)))
        }

        // The arrow lands on the first moving fix, not in the middle of the pile.
        assertEquals(11, stepScrubIndex(points, 0, +1))
        // Once next to moving fixes it goes back to single steps.
        assertEquals(12, stepScrubIndex(points, 11, +1))
        assertEquals(11, stepScrubIndex(points, 12, -1))
    }
}
