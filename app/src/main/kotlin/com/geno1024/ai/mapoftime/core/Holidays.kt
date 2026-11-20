package com.geno1024.ai.mapoftime.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One stretch of public holidays, both ends included. */
data class HolidayBlock(val first: LocalDate, val last: LocalDate) {
    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(first) && !date.isAfter(last)
}

/** Where a day sits relative to a public holiday. */
enum class HolidayPhase {
    /** Not near a holiday; the ordinary week the table measures. */
    NONE,

    /** The last day before a block starts: everyone leaves. */
    EVE,

    /** A holiday day that is not the last one. */
    DURING,

    /** The final day of a block: everyone comes back. */
    LAST,
}

/**
 * The mainland public holiday calendar, as announced by the State Council.
 *
 * Two things a plain weekday/weekend split gets wrong, and both matter here:
 * a holiday week drives like neither a weekday nor a weekend, and 调休 turns
 * some Saturdays into working days. Dates come from the yearly 国办发明电
 * notices; [coveredYears] says how far the table goes, and a date outside it
 * falls back to the plain weekday/weekend rule rather than to a guess.
 *
 * Adding a year is one block plus its makeup workdays. The app does not fetch
 * this: a wrong calendar would silently mislabel every trip in it.
 */
object Holidays {
    private fun block(first: LocalDate, last: LocalDate) = HolidayBlock(first, last)
    private fun date(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    /** Inclusive ranges, in calendar order. */
    val blocks: List<HolidayBlock> = listOf(
        // 2024
        block(date(2023, 12, 30), date(2024, 1, 1)),   // 元旦，与周末连休
        block(date(2024, 2, 10), date(2024, 2, 17)),   // 春节
        block(date(2024, 4, 4), date(2024, 4, 6)),     // 清明
        block(date(2024, 5, 1), date(2024, 5, 5)),     // 劳动节
        block(date(2024, 6, 8), date(2024, 6, 10)),    // 端午，与周末连休
        block(date(2024, 9, 15), date(2024, 9, 17)),   // 中秋
        block(date(2024, 10, 1), date(2024, 10, 7)),   // 国庆

        // 2025
        block(date(2025, 1, 1), date(2025, 1, 1)),     // 元旦，不调休
        block(date(2025, 1, 28), date(2025, 2, 4)),    // 春节
        block(date(2025, 4, 4), date(2025, 4, 6)),     // 清明
        block(date(2025, 5, 1), date(2025, 5, 5)),     // 劳动节
        block(date(2025, 5, 31), date(2025, 6, 2)),    // 端午
        block(date(2025, 10, 1), date(2025, 10, 8)),   // 国庆、中秋连休

        // 2026
        block(date(2026, 1, 1), date(2026, 1, 3)),     // 元旦
        block(date(2026, 2, 15), date(2026, 2, 23)),   // 春节
        block(date(2026, 4, 4), date(2026, 4, 6)),     // 清明
        block(date(2026, 5, 1), date(2026, 5, 5)),     // 劳动节
        block(date(2026, 6, 19), date(2026, 6, 21)),   // 端午
        block(date(2026, 9, 25), date(2026, 9, 27)),   // 中秋
        block(date(2026, 10, 1), date(2026, 10, 7)),   // 国庆
    )

    /** Saturdays and Sundays that the notice turns into working days. */
    val makeupWorkdays: Set<LocalDate> = setOf(
        // 2024
        date(2024, 2, 4), date(2024, 2, 18),
        date(2024, 4, 7),
        date(2024, 4, 28), date(2024, 5, 11),
        date(2024, 9, 14),
        date(2024, 9, 29), date(2024, 10, 12),
        // 2025
        date(2025, 1, 26), date(2025, 2, 8),
        date(2025, 4, 27),
        date(2025, 9, 28), date(2025, 10, 11),
        // 2026
        date(2026, 1, 4),
        date(2026, 2, 14), date(2026, 2, 28),
        date(2026, 5, 9),
        date(2026, 9, 20), date(2026, 10, 10),
    )

    /** Years the table knows about; outside it, days are just weekdays and weekends. */
    val coveredYears: IntRange = 2024..2026

    fun covers(date: LocalDate): Boolean = date.year in coveredYears

    fun covers(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        covers(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    fun blockAt(date: LocalDate): HolidayBlock? = blocks.firstOrNull { date in it }

    fun phaseOf(date: LocalDate): HolidayPhase {
        val block = blockAt(date)
        if (block != null) {
            return if (date == block.last) HolidayPhase.LAST else HolidayPhase.DURING
        }
        // The day before a block starts is the departure rush, whether or not
        // it is a weekend: 2026-02-14 is a Saturday that is also 春节's eve.
        if (blockAt(date.plusDays(1)) != null) return HolidayPhase.EVE
        return HolidayPhase.NONE
    }

    /** Working day, with 调休 honoured and holidays excluded. */
    fun isWorkday(date: LocalDate): Boolean = when {
        date in makeupWorkdays -> true
        blockAt(date) != null -> false
        else -> date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    }
}

/** The holiday phase of a moment, read on the local calendar. */
fun holidayPhaseOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): HolidayPhase =
    Holidays.phaseOf(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())
