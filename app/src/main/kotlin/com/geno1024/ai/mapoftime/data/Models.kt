package com.geno1024.ai.mapoftime.data

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode

enum class TripStatus { RECORDING, FINISHED, INTERRUPTED }

data class Trip(
    val id: Long,
    val mode: TripMode,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val pointCount: Int,
    val status: TripStatus,
    val note: String?,
    val legCount: Int = 0,
) {
    val durationMs: Long get() = ((endedAt ?: startedAt) - startedAt).coerceAtLeast(0)
}

/** How a leg got its identity; the UI says which is trustworthy. */
enum class MatchKind {
    /** Both ends are OSM intersections. The gold standard. */
    OSM,

    /** Both ends are locally clustered corners, no map data involved. */
    GEOMETRIC,

    /** One end is a real crossing, the other is the middle of a road (trip start/end). */
    PARTIAL,

    /** Nothing to anchor to — too short or entirely off-road. */
    UNMATCHED,
    ;

    companion object {
        fun of(name: String?): MatchKind = entries.firstOrNull { it.name == name } ?: UNMATCHED
    }
}

data class Leg(
    val id: Long,
    val tripId: Long,
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val segmentId: Long?,
    val direction: Int,
    val label: String?,
    val partial: Boolean,
    val polyline: String,
    val movingMs: Long,
    val stoppedMs: Long,
    val stopCount: Int,
    val longestStopMs: Long,
    /** Standstills that happened within [SIGNAL_RADIUS_METERS] of a traffic light. */
    val signalStopCount: Int = 0,
    val signalWaitMs: Long = 0,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    val hasWait: Boolean get() = stoppedMs > 0
}

/** A repeatable piece of road, keyed by its two endpoints. */
data class Segment(
    val id: Long,
    val key: String,
    val mode: TripMode,
    val name: String?,
    val identity: MatchKind,
    val aLat: Double,
    val aLon: Double,
    val bLat: Double,
    val bLon: Double,
    val lengthM: Double,
    val firstSeen: Long,
    val lastSeen: Long,
) {
    val from: LatLon get() = LatLon(aLat, aLon)
    val to: LatLon get() = LatLon(bLat, bLon)
}

/** A locally discovered corner, used when OSM data is unavailable. */
data class GeoNode(val id: Long, val at: LatLon, val lastSeen: Long)

/** What the analyzer produces for one leg before it is written. */
data class PendingLeg(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val segmentId: Long?,
    val direction: Int,
    val label: String?,
    val partial: Boolean,
    val polyline: String,
    val movingMs: Long,
    val stoppedMs: Long,
    val stopCount: Int,
    val longestStopMs: Long,
    val signalStopCount: Int = 0,
    val signalWaitMs: Long = 0,
)
