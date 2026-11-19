package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCounterTest {
    private val walk = TripMode.WALK.profile

    private fun timingOf(track: List<TrackPoint>): LegTiming {
        val result = Segmenter.segment(track, walk)
        val slice = result.legs.single()
        return StopCounter.of(
            points = result.points,
            speeds = Segmenter.speedsOf(result.points),
            slice = slice,
            profile = walk,
            startMs = result.points[slice.from].t,
            endMs = result.points[slice.to - 1].t,
        )
    }

    @Test
    fun waitingAtALightIsCountedSeparatelyFromTravel() {
        val track = TrackBuilder().straight(100.0).standStill(20).straight(100.0).build()
        val timing = timingOf(track)

        assertEquals(1, timing.stopCount)
        assertTrue(timing.stoppedMs in 19_000L..21_000L)
        assertTrue(timing.longestStopMs in 19_000L..21_000L)
        assertEquals(timing.totalMs, timing.movingMs + timing.stoppedMs)
        assertTrue(timing.movingMs > 0)
    }

    @Test
    fun aBriefDipInSpeedIsNotAWait() {
        val track = TrackBuilder().straight(50.0).standStill(2).straight(50.0).build()
        val timing = timingOf(track)

        assertEquals(0, timing.stopCount)
        assertEquals(0L, timing.stoppedMs)
        assertEquals(timing.totalMs, timing.movingMs)
    }

    @Test
    fun twoLightsAreTwoStops() {
        val track = TrackBuilder()
            .straight(80.0).standStill(12)
            .straight(80.0).standStill(8)
            .straight(80.0)
            .build()
        val timing = timingOf(track)

        assertEquals(2, timing.stopCount)
        assertTrue(timing.stoppedMs in 18_000L..22_000L)
        assertTrue(timing.longestStopMs in 11_000L..13_000L)
    }

    @Test
    fun aStopKnowsWhereItHappened() {
        val track = TrackBuilder().straight(100.0).standStill(20).straight(100.0).build()
        val result = Segmenter.segment(track, walk)
        val slice = result.legs.single()
        val spans = StopCounter.spans(
            points = result.points,
            speeds = Segmenter.speedsOf(result.points),
            slice = slice,
            profile = walk,
            endMs = result.points[slice.to - 1].t,
        )

        assertEquals(1, spans.size)
        // The builder starts at 39.9 and walks north, so the wait must be
        // somewhere along that line, not at the origin.
        assertTrue(spans.first().at.lat > 39.9)
        assertTrue(spans.first().durationMs in 19_000L..21_000L)
        assertEquals(spans.first().durationMs, timingOf(track).stoppedMs)
    }

    @Test
    fun aWaitThatEndsTheLegUsesTheLegEnd() {
        val track = TrackBuilder().straight(100.0).standStill(15).build()
        val timing = timingOf(track)

        assertEquals(1, timing.stopCount)
        assertTrue("stopped was ${timing.stoppedMs}", timing.stoppedMs >= 14_000L)
        assertEquals(timing.totalMs, timing.movingMs + timing.stoppedMs)
    }

    @Test
    fun aLongBreakNeverReachesTheCounter() {
        // 200 s is a pause: the segmenter ends the leg around it, so neither
        // side may report that waiting as travel or as a light.
        val result = Segmenter.segment(
            TrackBuilder().straight(100.0).standStill(200).straight(100.0).build(),
            walk,
        )
        assertEquals(2, result.legs.size)
        result.legs.forEach { slice ->
            val timing = StopCounter.of(
                result.points,
                Segmenter.speedsOf(result.points),
                slice,
                walk,
                result.points[slice.from].t,
                result.points[slice.to - 1].t,
            )
            assertEquals(0L, timing.stoppedMs)
        }
    }
}
