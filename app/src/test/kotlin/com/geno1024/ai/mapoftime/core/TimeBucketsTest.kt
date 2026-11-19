package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeBucketsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 19, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun theDayIsSplitAtItsBoundaries() {
        assertEquals(TimeBucket.NIGHT, TimeBucket.of(at(5, 59), zone))
        assertEquals(TimeBucket.MORNING, TimeBucket.of(at(6, 0), zone))
        assertEquals(TimeBucket.MORNING, TimeBucket.of(at(9, 59), zone))
        assertEquals(TimeBucket.DAY, TimeBucket.of(at(10, 0), zone))
        assertEquals(TimeBucket.DAY, TimeBucket.of(at(15, 59), zone))
        assertEquals(TimeBucket.EVENING, TimeBucket.of(at(16, 0), zone))
        assertEquals(TimeBucket.EVENING, TimeBucket.of(at(19, 59), zone))
        assertEquals(TimeBucket.NIGHT, TimeBucket.of(at(20, 0), zone))
        assertEquals(TimeBucket.NIGHT, TimeBucket.of(at(0, 0), zone))
    }

    @Test
    fun theZoneDecidesWhichLocalHourItIs() {
        val instant = at(21, 0)
        assertEquals(TimeBucket.NIGHT, TimeBucket.of(instant, zone))
        // Same moment, different wall clock: UTC is eight hours behind.
        assertEquals(TimeBucket.DAY, TimeBucket.of(instant, ZoneId.of("UTC")))
    }

    @Test
    fun samplesAreGroupedWithoutLosingAny() {
        val samples = listOf(
            TraversalSample(60_000, 100.0, 1, at(7)),
            TraversalSample(90_000, 100.0, 1, at(8)),
            TraversalSample(120_000, 100.0, 1, at(12)),
            TraversalSample(80_000, 100.0, -1, at(18)),
            TraversalSample(70_000, 100.0, 1, at(23)),
        )

        assertEquals(2, TimeBucket.MORNING.samples(samples, zone).size)
        assertEquals(1, TimeBucket.DAY.samples(samples, zone).size)
        assertEquals(1, TimeBucket.EVENING.samples(samples, zone).size)
        assertEquals(1, TimeBucket.NIGHT.samples(samples, zone).size)
        assertEquals(
            samples.size,
            TimeBucket.ordered.sumOf { it.samples(samples, zone).size },
        )
    }
}
