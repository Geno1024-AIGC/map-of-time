package com.geno1024.ai.mapoftime.track

import com.geno1024.ai.mapoftime.core.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceAccumulatorTest {
    private val start = LatLon(39.9, 116.3)

    /** [count] fixes [stepMeters] apart, straight north. */
    private fun steps(count: Int, stepMeters: Double): List<LatLon> =
        (0..count).map { LatLon(start.lat + it * stepMeters / 110_540.0, start.lon) }

    @Test
    fun walkingPaceAccumulatesInsteadOfDisappearing() {
        // 1.4 m per fix at 1 Hz for 100 s: every step is below the 1.5 m
        // threshold, which is exactly the case that used to report almost 0.
        val accumulator = DistanceAccumulator()
        steps(count = 100, stepMeters = 1.4).forEach { accumulator.add(it) }

        assertEquals(140.0, accumulator.meters, 1.0)
    }

    @Test
    fun standingStillDoesNotInventDistance() {
        val accumulator = DistanceAccumulator()
        val wobble = listOf(0.0, 0.2, -0.3, 0.1).map { LatLon(start.lat + it / 110_540.0, start.lon) }
        repeat(120) { wobble.forEach { point -> accumulator.add(point) } }

        assertEquals(0.0, accumulator.meters, 0.01)
    }

    @Test
    fun aBadFixCannotDrawAStraightLineAcrossTheMap() {
        val accumulator = DistanceAccumulator()
        accumulator.add(start)
        accumulator.add(step(500.0))

        assertEquals(0.0, accumulator.meters, 0.01)
        // The anchor moved to the new place, so the next honest step is small.
        accumulator.add(step(502.0))
        assertEquals(2.0, accumulator.meters, 0.1)
    }

    @Test
    fun poorAccuracyDoesNotThrowAwayTheWalk() {
        val accumulator = DistanceAccumulator()
        accumulator.add(start)
        // A fix we refuse to measure with must not move the anchor either.
        accumulator.add(step(20.0), trustworthy = false)
        accumulator.add(step(40.0), trustworthy = false)
        accumulator.add(step(60.0))
        accumulator.add(step(61.4))

        assertTrue("meters were ${accumulator.meters}", accumulator.meters >= 60.0)
    }

    private fun step(meters: Double): LatLon = LatLon(start.lat + meters / 110_540.0, start.lon)
}
