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

/**
 * One end of a segment: an OSM node or a locally clustered corner.
 *
 * The kind is part of the identity because an OSM node id and a geo node id
 * are numbered independently and would otherwise collide.
 */
data class PointRef(val kind: String, val nodeId: Long) {
    val key: String get() = "$kind:$nodeId"

    companion object {
        const val OSM = "osm"
        const val GEO = "geo"

        /** Parses `osm:123` / `geo:7`; anything else is not a point we can name. */
        fun parse(key: String?): PointRef? {
            val value = key ?: return null
            val kind = value.substringBefore(':', "")
            if (kind != OSM && kind != GEO) return null
            val id = value.substringAfter(':', "").toLongOrNull() ?: return null
            return PointRef(kind, id)
        }
    }
}

/** A point the user has given a name to. */
data class NamedPoint(val ref: PointRef, val name: String)

/**
 * What the settings' Danger Zone would remove, read fresh before each confirm
 * so the dialog never quotes a count from a stale list.
 */
data class DangerCounts(
    val pointNames: Int = 0,
    val places: Int = 0,
    val roadNames: Int = 0,
    val trips: Int = 0,
)

/**
 * One junction as the user thinks of it: a name plus every point that belongs
 * to it. A wide crossing is a dozen OSM nodes — an entry and an exit per lane,
 * a refuge at each corner — and further turning movements keep discovering
 * new ones. Naming the place once covers all of them, including the points
 * that do not exist yet.
 */
data class Place(
    val id: Long,
    val name: String,
    /** Centre of the member points; new points near it are adopted. */
    val at: LatLon,
    /** How far the members spread from the centre, so adoption has a bound. */
    val radiusM: Double,
    val points: List<PointRef> = emptyList(),
)

/**
 * "A × B" when both ends carry one atomic point name. One name alone says
 * nothing useful about the piece between the ends, so it stays null and the
 * next fallback wins.
 *
 * Two identical names are not a pair either: both ends of a short link inside
 * one junction carry the junction's name, and "X × X" describes nothing.
 *
 * A name that already contains × is a crossing of roads, not a point name.
 * Joining it with another end used to spell "gate × road × road" — three
 * parts, mixing a point with a crossing. Such a name belongs on [RoadNames]
 * as a list of roads, never here, so this returns null and the OSM name wins.
 *
 * The same × a derived crossing caption uses: two named ends, one separator
 * for every combination, so the app spells a pair the same way everywhere.
 */
fun pointPairName(from: String?, to: String?): String? =
    if (from != null && to != null && from != to &&
        !RoadNames.isComposite(from) && !RoadNames.isComposite(to)
    ) "$from × $to" else null

/** The same pair as a direction: "A → B"; composite road-crossing names are excluded. */
fun directionalName(from: String?, to: String?): String? =
    if (from != null && to != null && from != to &&
        !RoadNames.isComposite(from) && !RoadNames.isComposite(to)
    ) "$from → $to" else null

/**
 * The names to label the two ends of one traversal with, in the order the trip
 * went: a backward traversal starts at the segment's `to` end. Unnamed ends
 * fall back to the caller's generic "from"/"to" wording.
 */
fun traversalEndNames(
    segment: Segment,
    direction: Int,
    fromFallback: String,
    toFallback: String,
): Pair<String, String> = if (direction >= 0) {
    (segment.fromName ?: fromFallback) to (segment.toName ?: toFallback)
} else {
    (segment.toName ?: toFallback) to (segment.fromName ?: fromFallback)
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
    /**
     * A name the user typed. Kept apart from [name] so a re-analysis can
     * refresh what OSM says without throwing the user's label away.
     */
    val userName: String? = null,
    /** Identities of the two ends, e.g. `osm:123` / `geo:7`. */
    val fromKey: String? = null,
    val toKey: String? = null,
    /** Names the user gave the two ends; joined in from [fromKey] / [toKey]. */
    val fromName: String? = null,
    val toName: String? = null,
) {
    val from: LatLon get() = LatLon(aLat, aLon)
    val to: LatLon get() = LatLon(bLat, bLon)

    val fromPoint: PointRef? get() = PointRef.parse(fromKey)
    val toPoint: PointRef? get() = PointRef.parse(toKey)

    /**
     * The name the two atomic endpoint names spell out. Only when both ends
     * are named: "north gate × metro" is useful, "north gate × ..." is not.
     */
    val pointName: String? get() = pointPairName(fromName, toName)

    /**
     * A segment is named by its two ends. [userName] is a road name the user
     * typed as inference input for the points it touches — it no longer
     * overrides the point pair, which is what used to produce "gate × road ×
     * road". When neither end is named the OSM name is the fallback; the road
     * name stays available to the naming dialogs as inference input.
     */
    val preferredLabel: String? get() = pointName ?: name

    /** "A → B" for the forward direction (+1), when both ends are named. */
    val forwardName: String? get() = directionalName(fromName, toName)

    /** "B → A" for the backward direction (−1), when both ends are named. */
    val backwardName: String? get() = directionalName(toName, fromName)

    /** What every screen shows: the user's name, the point pair, OSM's, then the key. */
    val displayName: String get() = preferredLabel ?: key
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
