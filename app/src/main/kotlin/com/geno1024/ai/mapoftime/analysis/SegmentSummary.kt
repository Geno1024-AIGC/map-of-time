package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.BucketTake
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.TrafficContext
import com.geno1024.ai.mapoftime.data.Segment

/** A segment together with everything its samples say about it. */
data class SegmentSummary(
    val segment: Segment,
    val combined: DurationStats,
    val forward: DurationStats,
    val backward: DurationStats,
    /**
     * The typical time in each part of the week, per direction. The list uses
     * it to answer "how long does this take now"; the segment page recomputes
     * the full statistics from the samples instead.
     */
    val forwardTakes: Map<TrafficContext, BucketTake> = emptyMap(),
    val backwardTakes: Map<TrafficContext, BucketTake> = emptyMap(),
    /**
     * The Friday evening rush, per direction. Not in [forwardTakes]: those
     * buckets are the routine week, and a Friday evening is counted once.
     */
    val forwardFridayRush: BucketTake = BucketTake.NONE,
    val backwardFridayRush: BucketTake = BucketTake.NONE,
) {
    val sampleCount: Int get() = combined.count
}
