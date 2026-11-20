package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BucketTakeTest {
    private val peak = TrafficContext(DayKind.WEEKDAY, PeakHour.PEAK)
    private val offPeak = TrafficContext(DayKind.WEEKDAY, PeakHour.OFF_PEAK)

    @Test
    fun theTakeIsTheMedianAndTheCount() {
        val take = BucketTake.of(
            listOf(
                TraversalSample(60_000, 100.0, 1, 0),
                TraversalSample(90_000, 100.0, 1, 0),
                TraversalSample(120_000, 100.0, 1, 0),
            ),
        )
        assertEquals(90_000, take.medianMs)
        assertEquals(3, take.count)
    }

    @Test
    fun anEmptyBucketIsNoTakeAtAll() {
        assertEquals(BucketTake.NONE, BucketTake.of(emptyList()))
        assertEquals(0, BucketTake.NONE.count)
    }

    @Test
    fun aThinBucketIsNotOffered() {
        val takes = mapOf(peak to BucketTake(90_000, 2))
        // Two trips have a median but not a typical time: the caller falls
        // back to the pooled median instead of printing it.
        assertNull(takes.takeAt(peak))
    }

    @Test
    fun aBucketThatIsNotThereIsNotOffered() {
        assertNull(mapOf(peak to BucketTake(90_000, 10)).takeAt(offPeak))
    }

    @Test
    fun aBucketWithEnoughSamplesIsOffered() {
        val takes = mapOf(peak to BucketTake(90_000, 3))
        assertEquals(90_000L, takes.takeAt(peak)?.medianMs)
    }
}
