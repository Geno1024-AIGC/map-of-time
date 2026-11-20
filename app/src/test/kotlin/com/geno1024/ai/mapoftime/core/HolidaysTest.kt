package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(HolidayPhase.EVE, Holidays.builtIn.phaseOf(eve))
        assertTrue(Holidays.builtIn.isWorkday(eve))
    }

    @Test
    fun theLastDayOfABlockIsTheReturnRush() {
        assertEquals(HolidayPhase.DURING, Holidays.builtIn.phaseOf(LocalDate.of(2026, 2, 15)))
        assertEquals(HolidayPhase.DURING, Holidays.builtIn.phaseOf(LocalDate.of(2026, 2, 22)))
        assertEquals(HolidayPhase.LAST, Holidays.builtIn.phaseOf(LocalDate.of(2026, 2, 23)))
        assertEquals(HolidayPhase.NONE, Holidays.builtIn.phaseOf(LocalDate.of(2026, 2, 24)))
    }

    @Test
    fun everyBlockHasOneEveAndOneLastDay() {
        for (block in Holidays.blocks) {
            assertEquals(HolidayPhase.EVE, Holidays.builtIn.phaseOf(block.first.minusDays(1)))
            assertEquals(HolidayPhase.LAST, Holidays.builtIn.phaseOf(block.last))
        }
    }

    @Test
    fun twoHolidaysCloseTogetherKeepTheDaysBetweenThem() {
        // 2026: 中秋 9/25–27, then 10/1–7. 9/28–30 are ordinary working days,
        // but 9/30 is still 国庆's eve.
        assertEquals(HolidayPhase.LAST, Holidays.builtIn.phaseOf(LocalDate.of(2026, 9, 27)))
        assertEquals(HolidayPhase.NONE, Holidays.builtIn.phaseOf(LocalDate.of(2026, 9, 28)))
        assertEquals(HolidayPhase.EVE, Holidays.builtIn.phaseOf(LocalDate.of(2026, 9, 30)))
        assertEquals(HolidayPhase.DURING, Holidays.builtIn.phaseOf(LocalDate.of(2026, 10, 1)))
        assertEquals(HolidayPhase.LAST, Holidays.builtIn.phaseOf(LocalDate.of(2026, 10, 7)))
    }

    @Test
    fun makeupSaturdaysAreWorkingDays() {
        // 2026-01-04 is a Sunday the notice turns into a working day.
        val sunday = LocalDate.of(2026, 1, 4)
        assertTrue(Holidays.builtIn.isWorkday(sunday))
        assertEquals(DayKind.WEEKDAY, DayKind.ofByDate(sunday))
        assertFalse(Holidays.builtIn.isWorkday(LocalDate.of(2026, 1, 3)))
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
        assertEquals(HolidayPhase.LAST, Holidays.builtIn.phaseOf(LocalDate.of(2024, 1, 1)))
        assertEquals(HolidayPhase.EVE, Holidays.builtIn.phaseOf(LocalDate.of(2023, 12, 29)))
    }

    @Test
    fun theTableSaysHowFarItGoes() {
        assertFalse(Holidays.builtIn.covers(LocalDate.of(2023, 6, 1)))
        assertTrue(Holidays.builtIn.covers(LocalDate.of(2025, 6, 1)))
        assertTrue(Holidays.builtIn.covers(LocalDate.of(2026, 6, 1)))
        assertFalse(Holidays.builtIn.covers(LocalDate.of(2027, 6, 1)))
        assertTrue(Holidays.builtIn.covers(epoch(16, 8)))
    }

    @Test
    fun addingDaysToANationalHolidayMovesTheReturnRush() {
        // A company gives 10/8 and 10/9 as well. The two stretches touch, so
        // they are one holiday: still one return rush, now on the 9th.
        val calendar = HolidayCalendar.of(
            customBlocks = listOf(HolidayBlock(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9))),
            customWorkdays = emptySet(),
        )
        assertEquals(HolidayPhase.DURING, calendar.phaseOf(LocalDate.of(2026, 10, 8)))
        assertEquals(HolidayPhase.LAST, calendar.phaseOf(LocalDate.of(2026, 10, 9)))
        assertEquals(HolidayPhase.NONE, calendar.phaseOf(LocalDate.of(2026, 10, 10)))
        assertFalse(calendar.isWorkday(LocalDate.of(2026, 10, 8)))
    }

    @Test
    fun aCustomBlockReachesIntoAYearTheTableDoesNotKnow() {
        val calendar = HolidayCalendar.of(
            customBlocks = listOf(HolidayBlock(LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 12))),
            customWorkdays = emptySet(),
        )
        assertEquals(HolidayPhase.EVE, calendar.phaseOf(LocalDate.of(2027, 2, 5)))
        assertEquals(HolidayPhase.LAST, calendar.phaseOf(LocalDate.of(2027, 2, 12)))
        assertFalse(calendar.isWorkday(LocalDate.of(2027, 2, 8)))
        // Adding 2027 春节 does not claim to know the rest of 2027, so the
        // screens keep saying that the built-in table stops at 2026.
        assertFalse(calendar.covers(LocalDate.of(2027, 2, 8)))
    }

    @Test
    fun aCustomWorkdayTurnsASaturdayIntoAWeekday() {
        // 2027-03-06 is a Saturday. Someone who works it wants their commute
        // pooled with the other commutes, not with Saturday errands.
        val saturday = LocalDate.of(2027, 3, 6)
        assertEquals(DayKind.WEEKEND, DayKind.ofByDate(saturday))
        val calendar = HolidayCalendar.of(emptyList(), setOf(saturday))
        assertEquals(DayKind.WEEKDAY, DayKind.ofByDate(saturday, calendar))
    }

    @Test
    fun aBlockIsWrittenAndReadBack() {
        val blocks = listOf(
            HolidayBlock(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3)),
            HolidayBlock(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 1)),
        )
        assertEquals("2027-01-01..2027-01-03;2027-05-01", formatHolidayBlocks(blocks))
        assertEquals(blocks, parseHolidayBlocks(formatHolidayBlocks(blocks)))
    }

    @Test
    fun oneDayIsTwoFieldsOrOne() {
        val day = HolidayBlock(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1))
        assertEquals(day, parseHolidayBlock("2027-01-01"))
        assertEquals(day, parseHolidayBlock("2027-01-01..2027-01-01"))
    }

    @Test
    fun aTypoIsNotABlock() {
        assertNull(parseHolidayBlock("2027-1-1"))
        assertNull(parseHolidayBlock("2027-01-03..2027-01-01"))
        assertNull(parseHolidayBlock(""))
        assertNull(parseHolidayBlock("2027-01-01..2027-01-03..2027-01-05"))
        // One bad piece must not leave the good ones half-applied.
        assertNull(parseHolidayBlocks("2027-01-01..2027-01-03;nope"))
        // A trailing separator is just a trailing separator.
        assertEquals(1, parseHolidayBlocks("2027-01-01..2027-01-03;")?.size)
    }

    @Test
    fun workdaysAreWrittenAndReadBack() {
        val days = setOf(LocalDate.of(2027, 1, 4), LocalDate.of(2027, 2, 15))
        assertEquals("2027-01-04;2027-02-15", formatHolidayDates(days))
        assertEquals(days, parseHolidayDates("2027-01-04, 2027-02-15"))
        assertNull(parseHolidayDates("2027-01-04, 2027-02-30"))
    }

    @Test
    fun outsideTheTableThePlainWeekdayRuleTakesOver() {
        // 2030-01-01 is a Tuesday New Year's Day, but the app has no data for
        // it: claiming it is a holiday would be a guess, so it is a weekday.
        val unknown = LocalDate.of(2030, 1, 1)
        assertEquals(HolidayPhase.NONE, Holidays.builtIn.phaseOf(unknown))
        assertEquals(DayKind.WEEKDAY, DayKind.ofByDate(unknown))
    }
}
