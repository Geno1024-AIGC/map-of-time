package com.geno1024.ai.mapoftime.core

import java.time.Instant
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
