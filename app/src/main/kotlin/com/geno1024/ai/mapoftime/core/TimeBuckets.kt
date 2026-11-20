package com.geno1024.ai.mapoftime.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
        fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): DayKind =
            ofByDate(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

        fun ofByDate(date: LocalDate): DayKind =
            if (Holidays.isWorkday(date)) WEEKDAY else WEEKEND
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
        /** 07:00–08:59 and 17:00–18:59 local time. */
        val PEAK_HOURS = 7..8 to 17..18

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
        fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()) = TrafficContext(
            day = DayKind.of(epochMs, zone),
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
): List<TraversalSample> = samples.filter { TrafficContext.of(it.startedAt, zone) == this }

/**
 * The bucket's typical time when it holds enough samples to have one.
 * `null` means "not this one" — the caller falls back to the pooled median
 * rather than printing a median of two trips as if it meant something.
 */
fun Map<TrafficContext, BucketTake>.takeAt(context: TrafficContext): BucketTake? =
    this[context]?.takeIf { !it.isThin }

/**
 * Sorts [samples] into the four contexts in one pass, so a page with four
 * buckets and a few hundred traversals does not convert every timestamp four
 * times over.
 */
fun groupByTrafficContext(
    samples: List<TraversalSample>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<TrafficContext, List<TraversalSample>> = samples.groupBy { TrafficContext.of(it.startedAt, zone) }
