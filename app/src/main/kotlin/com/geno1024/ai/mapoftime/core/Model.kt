package com.geno1024.ai.mapoftime.core

/**
 * How the trip was made. Times are only comparable within the same mode, so
 * the mode is part of every segment's identity rather than a tag on a sample.
 */
enum class TripMode(val id: String) {
    WALK("walk"),
    BIKE("bike"),
    DRIVE("drive"),
    ;

    /**
     * Parameters that turn a noisy track into legs. They differ by an order of
     * magnitude in speed, so one set cannot serve all three.
     */
    val profile: ModeProfile get() = when (this) {
        WALK -> ModeProfile(
            maxSpeedMps = 4.5,
            stopSpeedMps = 0.5,
            stopAsPartOfLegMs = 90_000,
            turnDegrees = 65.0,
            minLegMeters = 20.0,
            minLegMs = 15_000,
            minLegForSplitMeters = 8.0,
        )
        BIKE -> ModeProfile(
            maxSpeedMps = 16.0,
            stopSpeedMps = 0.9,
            stopAsPartOfLegMs = 120_000,
            turnDegrees = 55.0,
            minLegMeters = 35.0,
            minLegMs = 12_000,
            minLegForSplitMeters = 12.0,
        )
        DRIVE -> ModeProfile(
            maxSpeedMps = 55.0,
            stopSpeedMps = 1.2,
            stopAsPartOfLegMs = 150_000,
            turnDegrees = 50.0,
            minLegMeters = 60.0,
            minLegMs = 10_000,
            minLegForSplitMeters = 20.0,
        )
    }

    companion object {
        fun of(id: String?): TripMode = entries.firstOrNull { it.id == id } ?: WALK
    }
}

data class ModeProfile(
    /** Anything faster than this is a GPS jump, not travel. */
    val maxSpeedMps: Double,
    /** Below this you are standing still. */
    val stopSpeedMps: Double,
    /**
     * A standstill shorter than this stays inside the leg — waiting at a light
     * is part of what that segment costs. Longer than this is a break: the leg
     * ends, and neither side absorbs the waiting time.
     */
    val stopAsPartOfLegMs: Long,
    /** Heading change that counts as turning into another road. */
    val turnDegrees: Double,
    val minLegMeters: Double,
    val minLegMs: Long,
    /** Shorter pieces are never produced when splitting at junctions. */
    val minLegForSplitMeters: Double,
)

/** One GPS fix, already stripped down to what matters. */
data class TrackPoint(
    val t: Long,
    val at: LatLon,
    val accuracy: Double = 0.0,
    val speed: Double? = null,
    val bearing: Double? = null,
)
