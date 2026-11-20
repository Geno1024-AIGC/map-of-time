package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {
    /** 2026-11-16 is a Monday, so 21 and 22 are its Saturday and Sunday. */
    private fun epoch(day: Int, hour: Int): Long =
        java.time.LocalDateTime.of(2026, 11, day, hour, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

    @Test
    fun theDayBeforeABlockIsTheDepartureRush() {
        // 2026 春节 starts on 2/15; 2/14 is a Saturday the notice turns into
        // a working day, which is exactly why the two axes are separate.
        val eve = LocalDate.of(2026, 2, 14)
        assertEquals(HolidayPhase.EVE, Holidays.phaseOf(eve))
        assertTrue(Holidays.isWorkday(eve))
    }

    @Test
    fun theLastDayOfABlockIsTheReturnRush() {
        assertEquals(HolidayPhase.DURING, Holidays.phaseOf(LocalDate.of(2026, 2, 15)))
        assertEquals(HolidayPhase.DURING, Holidays.phaseOf(LocalDate.of(2026, 2, 22)))
        assertEquals(HolidayPhase.LAST, Holidays.phaseOf(LocalDate.of(2026, 2, 23)))
        assertEquals(HolidayPhase.NONE, Holidays.phaseOf(LocalDate.of(2026, 2, 24)))
    }

    @Test
    fun everyBlockHasOneEveAndOneLastDay() {
        for (block in Holidays.blocks) {
            assertEquals(HolidayPhase.EVE, Holidays.phaseOf(block.first.minusDays(1)))
            assertEquals(HolidayPhase.LAST, Holidays.phaseOf(block.last))
        }
    }

    @Test
    fun twoHolidaysCloseTogetherKeepTheDaysBetweenThem() {
        // 2026: 中秋 9/25–27, then 10/1–7. 9/28–30 are ordinary working days,
        // but 9/30 is still 国庆's eve.
        assertEquals(HolidayPhase.LAST, Holidays.phaseOf(LocalDate.of(2026, 9, 27)))
        assertEquals(HolidayPhase.NONE, Holidays.phaseOf(LocalDate.of(2026, 9, 28)))
        assertEquals(HolidayPhase.EVE, Holidays.phaseOf(LocalDate.of(2026, 9, 30)))
        assertEquals(HolidayPhase.DURING, Holidays.phaseOf(LocalDate.of(2026, 10, 1)))
        assertEquals(HolidayPhase.LAST, Holidays.phaseOf(LocalDate.of(2026, 10, 7)))
    }

    @Test
    fun makeupSaturdaysAreWorkingDays() {
        // 2026-01-04 is a Sunday the notice turns into a working day.
        val sunday = LocalDate.of(2026, 1, 4)
        assertTrue(Holidays.isWorkday(sunday))
        assertEquals(DayKind.WEEKDAY, DayKind.ofByDate(sunday))
        assertFalse(Holidays.isWorkday(LocalDate.of(2026, 1, 3)))
    }

    @Test
    fun aHolidayIsNotAWeekendDay() {
        assertEquals(DayKind.WEEKEND, DayKind.ofByDate(LocalDate.of(2026, 2, 18)))
        // ...and the Monday inside the same block is not a working day either.
        assertEquals(DayKind.WEEKEND, DayKind.ofByDate(LocalDate.of(2026, 2, 16)))
        assertEquals(DayKind.WEEKDAY, DayKind.ofByDate(LocalDate.of(2026, 2, 24)))
    }

    @Test
    fun aBlockThatStartsInThePreviousYearStillHasItsLastDay() {
        // 2024 元旦 ran from 2023-12-30; 2024-01-01 is its last day.
        assertEquals(HolidayPhase.LAST, Holidays.phaseOf(LocalDate.of(2024, 1, 1)))
        assertEquals(HolidayPhase.EVE, Holidays.phaseOf(LocalDate.of(2023, 12, 29)))
    }

    @Test
    fun theTableSaysHowFarItGoes() {
        assertFalse(Holidays.covers(LocalDate.of(2023, 6, 1)))
        assertTrue(Holidays.covers(LocalDate.of(2025, 6, 1)))
        assertTrue(Holidays.covers(LocalDate.of(2026, 6, 1)))
        assertFalse(Holidays.covers(LocalDate.of(2027, 6, 1)))
        assertTrue(Holidays.covers(epoch(16, 8)))
    }

    @Test
    fun outsideTheTableThePlainWeekdayRuleTakesOver() {
        // 2030-01-01 is a Tuesday New Year's Day, but the app has no data for
        // it: claiming it is a holiday would be a guess, so it is a weekday.
        val unknown = LocalDate.of(2030, 1, 1)
        assertEquals(HolidayPhase.NONE, Holidays.phaseOf(unknown))
        assertEquals(DayKind.WEEKDAY, DayKind.ofByDate(unknown))
    }
}
