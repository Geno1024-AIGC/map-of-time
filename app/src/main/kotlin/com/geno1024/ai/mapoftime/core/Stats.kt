package com.geno1024.ai.mapoftime.core

/** One traversal of a segment, as stored on a leg. */
data class TraversalSample(
    val durationMs: Long,
    val distanceM: Double,
    val direction: Int,
    val startedAt: Long,
    /** Legs recorded before the split existed count all of it as movement. */
    val movingMs: Long = durationMs,
    val stoppedMs: Long = 0,
    val stopCount: Int = 0,
)

/** How much the samples agree; the whole point of collecting several. */
enum class Confidence {
    /** Fewer than [MIN_SAMPLES] samples: no typical value yet. */
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

data class DurationStats(
    val count: Int,
    val medianMs: Long,
    /** Typical time actually spent moving, i.e. [medianMs] minus the waits. */
    val medianMovingMs: Long,
    /** Typical wait of a random traversal; 0 when the light usually is green. */
    val medianStoppedMs: Long,
    /** Typical wait among the traversals that did have to wait. */
    val medianWaitedMs: Long,
    /** How many traversals recorded at least one standstill. */
    val waitedSamples: Int,
    val meanMs: Long,
    val trimmedMeanMs: Long,
    val p25Ms: Long,
    val p75Ms: Long,
    val minMs: Long,
    val maxMs: Long,
    val medianAbsoluteDeviationMs: Long,
    val outlierCount: Int,
    val medianDistanceM: Double,
    val confidence: Confidence,
) {
    /** The number the app calls "typical": the median, robust to a single bad trip. */
    val typicalMs: Long get() = medianMs

    /** Interquartile range over the median, i.e. spread without the tails. */
    val relativeSpread: Double
        get() = if (medianMs <= 0) 0.0 else (p75Ms - p25Ms).toDouble() / medianMs

    /** Seconds per kilometre of the typical traversal. */
    val typicalPaceSecondsPerKm: Double
        get() = if (medianDistanceM <= 1.0) 0.0 else medianMs / 1000.0 / (medianDistanceM / 1000.0)

    companion object {
        const val MIN_SAMPLES = 3

        val EMPTY = DurationStats(
            count = 0,
            medianMs = 0,
            medianMovingMs = 0,
            medianStoppedMs = 0,
            medianWaitedMs = 0,
            waitedSamples = 0,
            meanMs = 0,
            trimmedMeanMs = 0,
            p25Ms = 0,
            p75Ms = 0,
            minMs = 0,
            maxMs = 0,
            medianAbsoluteDeviationMs = 0,
            outlierCount = 0,
            medianDistanceM = 0.0,
            confidence = Confidence.NONE,
        )

        fun of(samples: List<TraversalSample>): DurationStats {
            if (samples.isEmpty()) return EMPTY
            val durations = samples.map { it.durationMs }.sorted()
            val distances = samples.map { it.distanceM }.sorted()
            val moving = samples.map { it.movingMs }.sorted()
            val stopped = samples.map { it.stoppedMs }.sorted()
            val waits = stopped.filter { it > 0 }
            val median = percentile(durations, 50.0)
            val p25 = percentile(durations, 25.0)
            val p75 = percentile(durations, 75.0)
            val iqr = p75 - p25
            val lowFence = p25 - (iqr * 1.5).toLong()
            val highFence = p75 + (iqr * 1.5).toLong()
            val outliers = durations.count { it < lowFence || it > highFence }
            val deviations = durations.map { kotlin.math.abs(it - median) }.sorted()

            return DurationStats(
                count = durations.size,
                medianMs = median,
                medianMovingMs = percentile(moving, 50.0),
                medianStoppedMs = percentile(stopped, 50.0),
                medianWaitedMs = percentile(waits, 50.0),
                waitedSamples = waits.size,
                meanMs = durations.average().toLong(),
                trimmedMeanMs = trimmedMean(durations),
                p25Ms = p25,
                p75Ms = p75,
                minMs = durations.first(),
                maxMs = durations.last(),
                medianAbsoluteDeviationMs = percentile(deviations, 50.0),
                outlierCount = outliers,
                medianDistanceM = percentile(distances, 50.0),
                confidence = confidenceOf(durations.size, median, iqr),
            )
        }

        private fun confidenceOf(count: Int, median: Long, iqr: Long): Confidence {
            if (count < MIN_SAMPLES || median <= 0) return Confidence.NONE
            val spread = iqr.toDouble() / median
            return when {
                count >= 5 && spread <= 0.25 -> Confidence.HIGH
                spread <= 0.5 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
        }

        /** Average after chopping 10 % off each tail; only meaningful from five samples up. */
        private fun trimmedMean(sorted: List<Long>): Long {
            if (sorted.isEmpty()) return 0
            val trim = if (sorted.size >= 5) (sorted.size / 10).coerceAtLeast(1) else 0
            val kept = sorted.subList(trim, sorted.size - trim)
            return kept.average().toLong()
        }

        /** Linear interpolation between closest ranks, the usual definition. */
        fun percentile(sorted: List<Long>, percent: Double): Long {
            if (sorted.isEmpty()) return 0
            if (sorted.size == 1) return sorted[0]
            val rank = (percent / 100.0) * (sorted.size - 1)
            val low = kotlin.math.floor(rank).toInt()
            val high = kotlin.math.ceil(rank).toInt()
            if (low == high) return sorted[low]
            val fraction = rank - low
            return (sorted[low] + (sorted[high] - sorted[low]) * fraction).toLong()
        }

        fun percentile(sorted: List<Double>, percent: Double): Double {
            if (sorted.isEmpty()) return 0.0
            if (sorted.size == 1) return sorted[0]
            val rank = (percent / 100.0) * (sorted.size - 1)
            val low = kotlin.math.floor(rank).toInt()
            val high = kotlin.math.ceil(rank).toInt()
            if (low == high) return sorted[low]
            val fraction = rank - low
            return sorted[low] + (sorted[high] - sorted[low]) * fraction
        }
    }
}
