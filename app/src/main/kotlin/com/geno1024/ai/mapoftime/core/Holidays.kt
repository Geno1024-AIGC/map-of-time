package com.geno1024.ai.mapoftime.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One stretch of public holidays, both ends included. */
data class HolidayBlock(val first: LocalDate, val last: LocalDate) {
    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(first) && !date.isAfter(last)

    /** `2026-10-01..2026-10-07`, the shape preferences and the settings field use. */
    override fun toString(): String =
        if (first == last) "$first" else "$first..$last"
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
 * A holiday calendar: the shipped State Council table, plus what the user added.
 *
 * The app does not fetch this. A wrong calendar would silently relabel every
 * trip in it, and the notices come out once a year — so the table ships, and
 * anything it does not know (next year's 春节, leave an employer hands out) is
 * something the user types in once instead of waiting for a release.
 *
 * Additions only ever add: a custom block cannot cancel a shipped holiday.
 * Extending one is the common case (a company that gives two more days), and
 * [merge] folds touching blocks into one stretch so such an extension still
 * has exactly one return rush.
 */
data class HolidayCalendar(
    val blocks: List<HolidayBlock>,
    val makeupWorkdays: Set<LocalDate>,
    /** Years the shipped table covers; outside them an unlisted day is a plain day. */
    val builtInYears: IntRange,
) {
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

    /**
     * Whether the shipped table has anything to say about [date]'s year. False
     * does not make the day wrong, it makes it a plain weekday — which the
     * screens say out loud instead of guessing at a holiday.
     */
    fun covers(date: LocalDate): Boolean = date.year in builtInYears

    fun covers(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        covers(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    companion object {
        /** The shipped calendar with [customBlocks] and [customWorkdays] folded in. */
        fun of(
            customBlocks: List<HolidayBlock>,
            customWorkdays: Set<LocalDate>,
        ): HolidayCalendar {
            val builtIn = Holidays.builtIn
            return HolidayCalendar(
                blocks = merge(builtIn.blocks + customBlocks),
                makeupWorkdays = builtIn.makeupWorkdays + customWorkdays,
                builtInYears = builtIn.builtInYears,
            )
        }

        /**
         * Overlapping or adjacent blocks are one stretch: 国庆 plus two days the
         * employer added is still one departure and one return, not two.
         */
        private fun merge(blocks: List<HolidayBlock>): List<HolidayBlock> {
            if (blocks.isEmpty()) return blocks
            val sorted = blocks.sortedBy { it.first }
            val merged = mutableListOf(sorted.first())
            for (block in sorted.drop(1)) {
                val last = merged.last()
                if (block.first.isAfter(last.last.plusDays(1))) {
                    merged += block
                } else {
                    merged[merged.lastIndex] = HolidayBlock(last.first, maxOf(last.last, block.last))
                }
            }
            return merged
        }
    }
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
 * Adding a year is one block plus its makeup workdays — or, without a release,
 * one entry in Settings (see [HolidayCalendar.of]).
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

    /** The shipped calendar, before anything the user added. */
    val builtIn: HolidayCalendar by lazy { HolidayCalendar(blocks, makeupWorkdays, coveredYears) }
}

/** The holiday phase of a moment, read on the local calendar. */
fun holidayPhaseOf(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    calendar: HolidayCalendar = Holidays.builtIn,
): HolidayPhase = calendar.phaseOf(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

/**
 * Parses one range as typed in Settings: `2026-10-01..2026-10-07`, or a single
 * `2026-10-01` for a one-day holiday. `null` for anything else, including a
 * reversed range — a typo must not silently become a holiday.
 */
fun parseHolidayBlock(text: String): HolidayBlock? {
    val bounds = text.trim().split("..").map { it.trim() }
    if (bounds.isEmpty() || bounds.size > 2) return null
    val first = runCatching { LocalDate.parse(bounds[0]) }.getOrNull() ?: return null
    if (bounds.size == 1) return HolidayBlock(first, first)
    val last = runCatching { LocalDate.parse(bounds[1]) }.getOrNull() ?: return null
    if (last.isBefore(first)) return null
    return HolidayBlock(first, last)
}

/**
 * Parses a `;`-separated list of ranges; blank pieces are ignored so a trailing
 * separator is not an error. `null` when any piece fails to parse.
 */
fun parseHolidayBlocks(text: String): List<HolidayBlock>? {
    val pieces = text.split(';', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val blocks = mutableListOf<HolidayBlock>()
    for (piece in pieces) {
        blocks += parseHolidayBlock(piece) ?: return null
    }
    return blocks
}

/** The inverse of [parseHolidayBlocks], for the preferences and the settings field. */
fun formatHolidayBlocks(blocks: List<HolidayBlock>): String =
    blocks.joinToString(";") { it.toString() }

/**
 * Parses a list of single dates, `,` / `;` / whitespace separated. Used for the
 * weekends the user's employer turns into working days.
 */
fun parseHolidayDates(text: String): Set<LocalDate>? {
    val pieces = text.split(',', ';', '\n', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    val dates = mutableSetOf<LocalDate>()
    for (piece in pieces) {
        dates += runCatching { LocalDate.parse(piece) }.getOrNull() ?: return null
    }
    return dates
}

/** The inverse of [parseHolidayDates]. */
fun formatHolidayDates(dates: Set<LocalDate>): String =
    dates.sortedBy { it }.joinToString(";") { it.toString() }
