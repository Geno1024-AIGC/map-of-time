package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private fun samples(vararg seconds: Long) = seconds.mapIndexed { i, s ->
        TraversalSample(s * 1000, 100.0, 1, i.toLong())
    }

    @Test
    fun emptySamplesHaveNoTypicalValue() {
        assertEquals(0, DurationStats.of(emptyList()).count)
        assertEquals(Confidence.NONE, DurationStats.of(emptyList()).confidence)
        assertEquals(0L, DurationStats.of(emptyList()).typicalMs)
    }

    @Test
    fun medianIsTheTypicalValue() {
        val stats = DurationStats.of(samples(80, 120, 100))
        assertEquals(3, stats.count)
        assertEquals(100_000L, stats.typicalMs)
        assertEquals(100_000L, stats.meanMs)
        assertEquals(80_000L, stats.minMs)
        assertEquals(120_000L, stats.maxMs)
    }

    @Test
    fun oneBadTripDoesNotMoveTheMedian() {
        val stats = DurationStats.of(samples(100, 102, 98, 101, 99, 900))
        assertEquals(100_500L, stats.typicalMs)
        assertTrue(stats.outlierCount >= 1)
    }

    @Test
    fun iqrIgnoresTails() {
        val stats = DurationStats.of(samples(10, 20, 30, 40, 1000))
        assertEquals(20_000L, stats.p25Ms)
        assertEquals(40_000L, stats.p75Ms)
        assertEquals(1, stats.outlierCount)
    }

    @Test
    fun fewerThanThreeSamplesAreNeverConfident() {
        assertEquals(Confidence.NONE, DurationStats.of(samples(100, 101)).confidence)
        // Three or four consistent trips already count, but never as "high".
        assertEquals(Confidence.MEDIUM, DurationStats.of(samples(100, 101, 102, 103)).confidence)
    }

    @Test
    fun consistentTripsScoreHigherThanErraticOnes() {
        val tight = DurationStats.of(samples(100, 102, 98, 101, 99, 100))
        val loose = DurationStats.of(samples(60, 140, 50, 150, 70, 130))
        assertEquals(Confidence.HIGH, tight.confidence)
        assertTrue(loose.confidence < tight.confidence)
    }

    @Test
    fun trimmedMeanDropsTheExtremes() {
        val stats = DurationStats.of(samples(10, 100, 100, 100, 100, 100, 100, 100, 100, 1000))
        assertTrue(stats.trimmedMeanMs < stats.meanMs)
        assertEquals(100_000L, stats.trimmedMeanMs)
        assertEquals(100_000L, stats.medianMs)
    }

    @Test
    fun paceUsesTheTypicalDistance() {
        val stats = DurationStats.of(
            listOf(
                TraversalSample(300_000, 1000.0, 1, 0),
                TraversalSample(360_000, 1000.0, 1, 1),
                TraversalSample(330_000, 1000.0, 1, 2),
            ),
        )
        assertEquals(330.0, stats.typicalPaceSecondsPerKm, 1.0)
    }

    @Test
    fun movingAndWaitingSplitAtTheMedian() {
        val stats = DurationStats.of(
            listOf(
                TraversalSample(100_000, 100.0, 1, 0, movingMs = 100_000),
                TraversalSample(110_000, 100.0, 1, 1, movingMs = 100_000, stoppedMs = 10_000),
                TraversalSample(120_000, 100.0, 1, 2, movingMs = 90_000, stoppedMs = 30_000),
            ),
        )
        assertEquals(100_000L, stats.medianMovingMs)
        assertEquals(10_000L, stats.medianStoppedMs)
        assertEquals(2, stats.waitedSamples)
        // When those two runs did stop, the middle wait was 20 s.
        assertEquals(20_000L, stats.medianWaitedMs)
    }

    @Test
    fun aRunWithoutStopsReportsNoWait() {
        val stats = DurationStats.of(samples(100, 102, 98))
        assertEquals(0L, stats.medianStoppedMs)
        assertEquals(0, stats.waitedSamples)
        assertEquals(0L, stats.medianWaitedMs)
        assertEquals(100_000L, stats.medianMovingMs)
    }

    @Test
    fun signalWaitsAreASeparateSubsetOfTheWait() {
        val stats = DurationStats.of(
            listOf(
                TraversalSample(100_000, 100.0, 1, 0, movingMs = 100_000),
                TraversalSample(130_000, 100.0, 1, 1, movingMs = 100_000, stoppedMs = 30_000, signalWaitMs = 30_000, signalStopCount = 1),
                TraversalSample(125_000, 100.0, 1, 2, movingMs = 105_000, stoppedMs = 20_000),
            ),
        )

        assertEquals(2, stats.waitedSamples)
        assertEquals(1, stats.signalWaitedSamples)
        assertEquals(30_000L, stats.medianSignalWaitedMs)
        // A random traversal waits 0 s at a light: only one of three did.
        assertEquals(0L, stats.medianSignalWaitMs)
    }

    @Test
    fun percentileInterpolates() {
        assertEquals(15L, DurationStats.percentile(listOf(10L, 20L), 50.0))
        assertEquals(10L, DurationStats.percentile(listOf(10L, 20L), 0.0))
        assertEquals(20L, DurationStats.percentile(listOf(10L, 20L), 100.0))
    }
}
