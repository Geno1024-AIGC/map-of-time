package com.geno1024.ai.mapoftime.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Part of the day a traversal happened in.
 *
 * The same road is not worth the same at eight in the morning and at eleven at
 * night, and the combined median hides that difference completely. These four
 * buckets are deliberately crude: with a handful of samples per segment, finer
 * buckets would only produce noise that looks like a finding.
 */
enum class TimeBucket {
    MORNING,
    DAY,
    EVENING,
    NIGHT,
    ;

    companion object {
        /** Hours are local to [zone]: a trip at 08:00 belongs to the morning wherever it was walked. */
        fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): TimeBucket =
            when (Instant.ofEpochMilli(epochMs).atZone(zone).hour) {
                in 6..9 -> MORNING
                in 10..15 -> DAY
                in 16..19 -> EVENING
                else -> NIGHT
            }

        /** The buckets in display order, earliest in the day first. */
        val ordered: List<TimeBucket> = listOf(MORNING, DAY, EVENING, NIGHT)
    }
}

/** The traversals that happened in this part of the day. */
fun TimeBucket.samples(
    samples: List<TraversalSample>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TraversalSample> = samples.filter { TimeBucket.of(it.startedAt, zone) == this }

/**
 * Whether the day was a working day.
 *
 * Monday morning and Saturday morning are the same road at the same hour and
 * nothing else about them is alike, so they must not be pooled. "Working" is
 * read from [Holidays], not from the day of the week: 调休 turns some Saturdays
 * into workdays, and a public holiday is not a weekend either.
 */
enum class DayKind {
    WEEKDAY,
    WEEKEND,
    ;

    companion object {
        fun of(
            epochMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            calendar: HolidayCalendar = Holidays.builtIn,
        ): DayKind = ofByDate(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate(), calendar)

        fun ofByDate(date: LocalDate, calendar: HolidayCalendar = Holidays.builtIn): DayKind =
            if (calendar.isWorkday(date)) WEEKDAY else WEEKEND
    }
}

/**
 * Rush hour or not.
 *
 * Morning and evening are collapsed on purpose: what matters for "is this trip
 * going to be slow" is that it happened in the crush, not which of the two
 * crushes it was. Four hours out of twenty-four is already a lot to ask of a
 * handful of samples.
 */
enum class PeakHour {
    PEAK,
    OFF_PEAK,
    ;

    companion object {
        /** 07:00–08:59 local time. */
        val MORNING_HOURS = 7..8

        /** 17:00–18:59 local time. */
        val EVENING_HOURS = 17..18

        /** Both rushes, as a pair of hour ranges. */
        val PEAK_HOURS = MORNING_HOURS to EVENING_HOURS

        fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): PeakHour {
            val hour = Instant.ofEpochMilli(epochMs).atZone(zone).hour
            return if (hour in PEAK_HOURS.first || hour in PEAK_HOURS.second) PEAK else OFF_PEAK
        }
    }
}

/**
 * The two axes crossed: four kinds of traversal that are worth telling apart.
 *
 * Any finer and the tables would be mostly empty cells that look like findings.
 */
data class TrafficContext(val day: DayKind, val peak: PeakHour) {
    companion object {
        fun of(
            epochMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            calendar: HolidayCalendar = Holidays.builtIn,
        ) = TrafficContext(
            day = DayKind.of(epochMs, zone, calendar),
            peak = PeakHour.of(epochMs, zone),
        )

        /** Weekday first, peak first: the order the table is read in. */
        val ordered: List<TrafficContext> = buildList {
            for (day in DayKind.entries) {
                for (peak in PeakHour.entries) add(TrafficContext(day, peak))
            }
        }
    }
}

/** The traversals that happened in this kind of day and hour. */
fun TrafficContext.samples(
    samples: List<TraversalSample>,
    zone: ZoneId = ZoneId.systemDefault(),
    calendar: HolidayCalendar = Holidays.builtIn,
): List<TraversalSample> =
    samples.filter { TrafficContext.of(it.startedAt, zone, calendar) == this }

/**
 * How the app splits the week for statistics.
 *
 * Holidays and the Friday evening rush are both systematically unlike an
 * ordinary weekday, and both are kept out of the 2×2 table so that each cell
 * still answers exactly one question. They are listed on their own instead —
 * holidays by phase, Friday as a single extra row.
 */
enum class TrafficDay {
    /** What the table measures: an ordinary working or resting day. */
    ROUTINE,

    /** Friday 17:00–19:00 on a working Friday that is not a holiday or its eve. */
    FRIDAY_RUSH,

    /** A holiday day, or the day before one. */
    HOLIDAY,
}

/**
 * Which of the three kinds of day [epochMs] falls on. The order matters: a
 * Friday that is a holiday eve is a holiday, not a rush hour — 2026-04-03 is
 * 清明's departure, and 2026-06-19 is 端午 itself.
 */
fun trafficDayOf(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    calendar: HolidayCalendar = Holidays.builtIn,
): TrafficDay {
    val local = Instant.ofEpochMilli(epochMs).atZone(zone)
    if (calendar.phaseOf(local.toLocalDate()) != HolidayPhase.NONE) return TrafficDay.HOLIDAY
    return if (isFridayRush(local, calendar)) TrafficDay.FRIDAY_RUSH else TrafficDay.ROUTINE
}

/** Friday 17:00–19:00, on a working Friday that is not a holiday or its eve. */
fun isFridayRush(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    calendar: HolidayCalendar = Holidays.builtIn,
): Boolean = isFridayRush(Instant.ofEpochMilli(epochMs).atZone(zone), calendar)

private fun isFridayRush(local: ZonedDateTime, calendar: HolidayCalendar): Boolean =
    local.dayOfWeek == DayOfWeek.FRIDAY &&
        local.hour in PeakHour.EVENING_HOURS &&
        calendar.isWorkday(local.toLocalDate())

/**
 * The bucket's typical time when it holds enough samples to have one.
 * `null` means "not this one" — the caller falls back to the pooled median
 * rather than printing a median of two trips as if it meant something.
 */
/** The take when it holds enough samples; `null` means "show the pooled median instead". */
fun BucketTake.takeOrNull(): BucketTake? = takeIf { !it.isThin }

fun Map<TrafficContext, BucketTake>.takeAt(context: TrafficContext): BucketTake? =
    this[context]?.takeOrNull()

/**
 * Sorts [samples] into the four contexts in one pass, so a page with four
 * buckets and a few hundred traversals does not convert every timestamp four
 * times over.
 */
fun groupByTrafficContext(
    samples: List<TraversalSample>,
    zone: ZoneId = ZoneId.systemDefault(),
    calendar: HolidayCalendar = Holidays.builtIn,
): Map<TrafficContext, List<TraversalSample>> =
    samples.groupBy { TrafficContext.of(it.startedAt, zone, calendar) }

/**
 * A free-flow reference for one direction, from the app's own GPS history.
 *
 * Routine off-peak trips are the closest thing to "nothing unusual happened";
 * holidays and Friday evening are already split out by [trafficDayOf]. When
 * there are too few of those, the fastest quarter of all trips is a rougher
 * stand-in. Fewer than [DurationStats.MIN_SAMPLES] trips produce no baseline:
 * a congestion flag needs something to be slow *compared with*.
 *
 * The caller passes one direction only. Pooling both directions would compare
 * an uphill trip with a downhill one and call the hill congestion.
 */
fun freeFlowBaselineMs(
    samples: List<TraversalSample>,
    zone: ZoneId = ZoneId.systemDefault(),
    calendar: HolidayCalendar = Holidays.builtIn,
): Long {
    if (samples.size < DurationStats.MIN_SAMPLES) return 0
    val offPeak = samples.filter {
        trafficDayOf(it.startedAt, zone, calendar) == TrafficDay.ROUTINE &&
            PeakHour.of(it.startedAt, zone) == PeakHour.OFF_PEAK
    }
    val reference = if (offPeak.size >= DurationStats.MIN_SAMPLES) offPeak else samples
    val percentile = if (offPeak.size >= DurationStats.MIN_SAMPLES) 50.0 else 25.0
    return DurationStats.percentile(reference.map { it.durationMs }.sorted(), percentile)
}
