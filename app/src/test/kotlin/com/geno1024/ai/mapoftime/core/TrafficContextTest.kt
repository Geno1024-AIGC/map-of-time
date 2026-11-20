package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TrafficContextTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-16 is a Monday, so 21 and 22 are its Saturday and Sunday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun saturdayAndSundayAreTheWeekend() {
        assertEquals(DayKind.WEEKEND, DayKind.of(at(21, 12), zone))
        assertEquals(DayKind.WEEKEND, DayKind.of(at(22, 12), zone))
        assertEquals(DayKind.WEEKDAY, DayKind.of(at(20, 12), zone))
        assertEquals(DayKind.WEEKDAY, DayKind.of(at(16, 12), zone))
    }

    @Test
    fun theTwoRushesArePeakAndNothingElseIs() {
        // Morning: 07:00–08:59.
        assertEquals(PeakHour.OFF_PEAK, PeakHour.of(at(16, 6, 59), zone))
        assertEquals(PeakHour.PEAK, PeakHour.of(at(16, 7, 0), zone))
        assertEquals(PeakHour.PEAK, PeakHour.of(at(16, 8, 59), zone))
        assertEquals(PeakHour.OFF_PEAK, PeakHour.of(at(16, 9, 0), zone))
        // Evening: 17:00–18:59.
        assertEquals(PeakHour.OFF_PEAK, PeakHour.of(at(16, 16, 59), zone))
        assertEquals(PeakHour.PEAK, PeakHour.of(at(16, 17, 0), zone))
        assertEquals(PeakHour.PEAK, PeakHour.of(at(16, 18, 59), zone))
        assertEquals(PeakHour.OFF_PEAK, PeakHour.of(at(16, 19, 0), zone))
        assertEquals(PeakHour.OFF_PEAK, PeakHour.of(at(16, 12), zone))
        assertEquals(PeakHour.OFF_PEAK, PeakHour.of(at(16, 23), zone))
    }

    @Test
    fun theContextIsTheTwoAxesCrossed() {
        assertEquals(
            TrafficContext(DayKind.WEEKDAY, PeakHour.PEAK),
            TrafficContext.of(at(16, 8), zone),
        )
        // Same hour, different day: Saturday morning is not a commute.
        assertEquals(
            TrafficContext(DayKind.WEEKEND, PeakHour.PEAK),
            TrafficContext.of(at(21, 8), zone),
        )
    }

    @Test
    fun theZoneDecidesWhichLocalDayAndHourItIs() {
        // 09:00 Monday in Shanghai is 01:00 Monday in UTC: off-peak there,
        // and the day is the same one, so only the hour moves.
        assertEquals(
            TrafficContext(DayKind.WEEKDAY, PeakHour.OFF_PEAK),
            TrafficContext.of(at(16, 9), ZoneId.of("UTC")),
        )
        // 07:30 Monday in Shanghai is 23:30 Sunday in UTC: a different day.
        assertEquals(
            TrafficContext(DayKind.WEEKEND, PeakHour.OFF_PEAK),
            TrafficContext.of(at(16, 7, 30), ZoneId.of("UTC")),
        )
    }

    @Test
    fun groupingKeepsEverySampleAndOnlyTheFourBuckets() {
        val samples = listOf(
            TraversalSample(60_000, 100.0, 1, at(16, 8)),
            TraversalSample(90_000, 100.0, 1, at(16, 12)),
            TraversalSample(120_000, 100.0, 1, at(17, 18)),
            TraversalSample(80_000, 100.0, -1, at(18, 23)),
            TraversalSample(70_000, 100.0, 1, at(21, 8)),
            TraversalSample(75_000, 100.0, 1, at(22, 12)),
        )

        val grouped = groupByTrafficContext(samples, zone)
        assertEquals(samples.size, grouped.values.sumOf { it.size })
        assertEquals(TrafficContext.ordered.toSet(), grouped.keys)
        assertEquals(2, grouped.getValue(TrafficContext(DayKind.WEEKDAY, PeakHour.PEAK)).size)
        assertEquals(2, grouped.getValue(TrafficContext(DayKind.WEEKDAY, PeakHour.OFF_PEAK)).size)
        assertEquals(1, grouped.getValue(TrafficContext(DayKind.WEEKEND, PeakHour.PEAK)).size)
        assertEquals(1, grouped.getValue(TrafficContext(DayKind.WEEKEND, PeakHour.OFF_PEAK)).size)
    }

    @Test
    fun theTableReadsWeekdaysFirstAndPeaksFirst() {
        assertEquals(
            listOf(
                TrafficContext(DayKind.WEEKDAY, PeakHour.PEAK),
                TrafficContext(DayKind.WEEKDAY, PeakHour.OFF_PEAK),
                TrafficContext(DayKind.WEEKEND, PeakHour.PEAK),
                TrafficContext(DayKind.WEEKEND, PeakHour.OFF_PEAK),
            ),
            TrafficContext.ordered,
        )
    }
}
