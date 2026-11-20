package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TrafficDayTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-16 is a Monday, so 20 is that week's Friday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun theFridayEveningRushIsItsOwnKindOfDay() {
        assertEquals(TrafficDay.FRIDAY_RUSH, trafficDayOf(at(20, 17), zone))
        assertEquals(TrafficDay.FRIDAY_RUSH, trafficDayOf(at(20, 18, 59), zone))
        // Only the evening half of the rush is special.
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(20, 8), zone))
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(20, 16, 59), zone))
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(20, 19), zone))
    }

    @Test
    fun theSameHourOnAnotherDayIsJustAWeekday() {
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(19, 18), zone))
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(16, 18), zone))
        // A Saturday 18:00 is the weekend, not a commute.
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(21, 18), zone))
    }

    @Test
    fun aFridayThatIsAHolidayIsAHolidayNotARushHour() {
        // 2026-06-19 is a Friday and the first day of 端午.
        val duanwu = LocalDate.of(2026, 6, 19)
        assertEquals(DayOfWeek.FRIDAY, duanwu.dayOfWeek)
        assertEquals(TrafficDay.HOLIDAY, trafficDayOf(at(duanwu, 18, zone), zone))
        assertEquals(false, isFridayRush(at(duanwu, 18, zone), zone))
    }

    @Test
    fun aFridayBeforeAHolidayIsTheDepartureRush() {
        // 2026-04-04 is a Saturday, so 04-03 is a Friday that is 清明's eve.
        // Leaving for the holiday outranks "it is Friday evening".
        assertEquals(TrafficDay.HOLIDAY, trafficDayOf(at(LocalDate.of(2026, 4, 3), 18, zone), zone))
    }

    @Test
    fun aCustomHolidayAlsoTakesAFridayEveningOutOfTheRush() {
        val friday = LocalDate.of(2027, 3, 5)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(TrafficDay.FRIDAY_RUSH, trafficDayOf(at(friday, 18, zone), zone))
        val calendar = HolidayCalendar.of(listOf(HolidayBlock(friday, friday)), emptySet())
        assertEquals(TrafficDay.HOLIDAY, trafficDayOf(at(friday, 18, zone), calendar = calendar))
    }

    @Test
    fun aMakeupSaturdayEveningIsStillNotAFriday() {
        // A 调休 Saturday is a working day, but it is not Friday: the bucket
        // is about the day of the week, not about whether people commute.
        val saturday = LocalDate.of(2026, 10, 10)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val calendar = HolidayCalendar.of(emptyList(), setOf(saturday))
        assertEquals(true, calendar.isWorkday(saturday))
        assertEquals(TrafficDay.ROUTINE, trafficDayOf(at(saturday, 18, zone), calendar = calendar))
    }

    private fun at(date: LocalDate, hour: Int, zone: ZoneId): Long =
        LocalDateTime.of(date, LocalTime.of(hour, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
