package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.data.Segment

/** A segment together with everything its samples say about it. */
data class SegmentSummary(
    val segment: Segment,
    val combined: DurationStats,
    val forward: DurationStats,
    val backward: DurationStats,
) {
    val sampleCount: Int get() = combined.count
}
