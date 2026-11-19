package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.SegmentKeys
import com.geno1024.ai.mapoftime.core.Segmenter
import com.geno1024.ai.mapoftime.core.SIGNAL_RADIUS_METERS
import com.geno1024.ai.mapoftime.core.StopCounter
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.MapRepository
import com.geno1024.ai.mapoftime.data.OsmRepository
import com.geno1024.ai.mapoftime.data.PendingLeg
import com.geno1024.ai.mapoftime.data.SettingsSnapshot
import com.geno1024.ai.mapoftime.data.TripStatus
import com.geno1024.ai.mapoftime.osm.JunctionSplitter
import com.geno1024.ai.mapoftime.osm.MatchedPiece
import com.geno1024.ai.mapoftime.osm.OsmSignal
import com.geno1024.ai.mapoftime.osm.RoadGraph

/** Why a trip did or did not get OSM junctions, so the UI can say it out loud. */
enum class OsmOutcome {
    /** Road data was available and legs were cut at real junctions. */
    MATCHED,

    /** The user turned road matching off, or the trace has no usable bounds. */
    DISABLED,

    /** The trip spans more tiles than one analysis may fetch. */
    TOO_LARGE,

    /** Nothing was cached and the request produced no data. */
    UNAVAILABLE,

    /** The tiles answered, but contain no highways at all. */
    NO_DATA,
}

/**
 * The whole post-processing chain for one finished trip:
 *
 * 1. raw fixes → cleaned trace → legs (turns, long stops, manual marks);
 * 2. legs → pieces cut at OSM intersections;
 * 3. pieces → segment identities (OSM junction pair, or locally clustered
 *    corners when there is no road data);
 * 4. everything written back so the statistics can aggregate it.
 *
 * Runs on the IO dispatcher; touches the network only to fill the OSM tile
 * cache, and degrades to the geometric fallback if that fails.
 */
class TripAnalyzer(
    private val repository: MapRepository,
    private val osm: OsmRepository,
    private val settings: () -> SettingsSnapshot,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        val tripId: Long,
        val legCount: Int,
        val osmLegs: Int,
        val partialLegs: Int,
        val geometricLegs: Int,
        val usedOsm: Boolean,
        val osmOutcome: OsmOutcome,
        val distanceM: Double,
        val pointCount: Int,
    )

    fun analyze(tripId: Long): Result? {
        val trip = repository.trip(tripId) ?: return null
        val raw = repository.points(tripId)
        val marks = repository.marks(tripId)
        val segmentation = Segmenter.segment(raw, trip.mode.profile, marks)
        // The same smoothed speeds the segmenter used, so a stop can never be
        // a pause to one stage and waiting time to the other.
        val speeds = Segmenter.speedsOf(segmentation.points)
        val now = clock()
        val config = settings()

        val bounds = Geo.bounds(segmentation.points.map { it.at })
        val searchBounds = if (config.osmMatching) bounds?.expanded(80.0) else null
        val tooLarge = searchBounds != null && !osm.canCover(searchBounds)
        val ways = if (searchBounds == null || tooLarge) {
            null
        } else {
            runCatching {
                osm.waysFor(searchBounds, config.osmCacheDays.toLong() * DAY_MS)
            }.getOrNull()
        }
        val graph = ways?.takeIf { it.isNotEmpty() }?.let { RoadGraph(it) }
        // Traffic lights only exist where the roads do; the tile cache has just
        // been filled, so this read is free.
        val signals = if (graph != null && searchBounds != null) {
            runCatching { osm.signalsFor(searchBounds) }.getOrDefault(emptyList())
        } else {
            emptyList<OsmSignal>()
        }
        val osmOutcome = when {
            searchBounds == null -> OsmOutcome.DISABLED
            tooLarge -> OsmOutcome.TOO_LARGE
            ways == null -> OsmOutcome.UNAVAILABLE
            ways.isEmpty() -> OsmOutcome.NO_DATA
            graph == null -> OsmOutcome.UNAVAILABLE
            else -> OsmOutcome.MATCHED
        }

        val legs = ArrayList<PendingLeg>()
        var osmLegs = 0
        var partialLegs = 0
        var geometricLegs = 0
        var distance = 0.0

        for (leg in segmentation.legs) {
            val pieces = JunctionSplitter.split(
                points = segmentation.points,
                leg = leg,
                graph = graph,
                snapMeters = config.junctionSnapMeters.toDouble(),
                minPieceMeters = trip.mode.profile.minLegForSplitMeters,
            )
            for (piece in pieces) {
                val analyzed = toLeg(piece, segmentation.points, speeds, trip.mode.id, graph, signals, now) ?: continue
                legs += analyzed.leg
                distance += analyzed.leg.distanceM
                when (analyzed.identity) {
                    MatchKind.OSM -> osmLegs++
                    MatchKind.PARTIAL -> partialLegs++
                    MatchKind.GEOMETRIC -> geometricLegs++
                    MatchKind.UNMATCHED -> Unit
                }
            }
        }

        repository.replaceLegs(tripId, legs)
        repository.updateProgress(tripId, distance, raw.size)
        val endedAt = raw.lastOrNull()?.t ?: trip.endedAt ?: trip.startedAt
        repository.finishTrip(tripId, endedAt, TripStatus.FINISHED)

        return Result(
            tripId = tripId,
            legCount = legs.size,
            osmLegs = osmLegs,
            partialLegs = partialLegs,
            geometricLegs = geometricLegs,
            usedOsm = graph != null,
            osmOutcome = osmOutcome,
            distanceM = distance,
            pointCount = raw.size,
        )
    }

    private data class LegResult(val leg: PendingLeg, val identity: MatchKind)

    private fun toLeg(
        piece: MatchedPiece,
        points: List<TrackPoint>,
        speeds: DoubleArray,
        modeId: String,
        graph: RoadGraph?,
        signals: List<OsmSignal>,
        now: Long,
    ): LegResult? {
        val fromIndex = piece.slice.from
        val toIndex = piece.slice.to - 1
        if (toIndex <= fromIndex) return null
        val startMs = crossingTime(points, fromIndex)
        val endMs = crossingTime(points, toIndex)
        if (endMs <= startMs) return null

        val geometry = points.subList(fromIndex, toIndex + 1).map { it.at }
        val meters = Geo.pathLengthMeters(geometry, minStep = 0.5)
        if (meters < MIN_PIECE_METERS) return null

        val mode = TripMode.of(modeId)
        val stops = StopCounter.spans(
            points = points,
            speeds = speeds,
            slice = piece.slice,
            profile = mode.profile,
            endMs = endMs,
        )
        val timing = StopCounter.timing((endMs - startMs).coerceAtLeast(0), stops)
        // A wait counts as a red light only when it happened where OSM put a
        // traffic signal. Every other standstill stays an anonymous wait.
        val atSignals = if (signals.isEmpty()) {
            emptyList()
        } else {
            stops.filter { stop ->
                signals.any { Geo.distanceMeters(stop.at, it.at) <= SIGNAL_RADIUS_METERS }
            }
        }
        val startJunction = piece.start.junctionId
        val endJunction = piece.end.junctionId
        val fullyBounded = startJunction != null && endJunction != null

        val (key, identity, partial) = when {
            fullyBounded -> Triple(SegmentKeys.osm(startJunction, endJunction), MatchKind.OSM, false)
            graph != null -> {
                val from = startJunction ?: clusterId(piece.start.at, now)
                val to = endJunction ?: clusterId(piece.end.at, now)
                Triple(SegmentKeys.mixed(from, to), MatchKind.PARTIAL, true)
            }
            else -> {
                val from = clusterId(piece.start.at, now)
                val to = clusterId(piece.end.at, now)
                Triple(SegmentKeys.geo(from, to), MatchKind.GEOMETRIC, false)
            }
        }

        val existing = repository.segmentByKey(key, mode)
        val direction = when {
            existing == null -> 1
            Geo.distanceMeters(geometry.first(), existing.from) <=
                Geo.distanceMeters(geometry.first(), existing.to) -> 1
            else -> -1
        }
        val segmentId = repository.insertSegment(
            key = key,
            mode = mode,
            name = piece.label,
            identity = identity,
            from = geometry.first(),
            to = geometry.last(),
            lengthM = meters,
            seenAt = now,
        )

        return LegResult(
            leg = PendingLeg(
                startMs = startMs,
                endMs = endMs,
                distanceM = meters,
                segmentId = segmentId,
                direction = direction,
                label = piece.label,
                partial = partial,
                polyline = Polyline.encode(geometry),
                movingMs = timing.movingMs,
                stoppedMs = timing.stoppedMs,
                stopCount = timing.stopCount,
                longestStopMs = timing.longestStopMs,
                signalStopCount = atSignals.size,
                signalWaitMs = atSignals.sumOf { it.durationMs },
            ),
            identity = identity,
        )
    }

    /**
     * Time at which the trace crossed [index]. A junction is shared by the two
     * pieces on either side, so both must read the same value — otherwise every
     * boundary would be counted twice.
     */
    private fun crossingTime(points: List<TrackPoint>, index: Int): Long = when {
        index <= 0 -> points[0].t
        index >= points.lastIndex -> points[points.lastIndex].t
        else -> (points[index - 1].t + points[index].t + points[index + 1].t) / 3
    }

    /**
     * Endpoint in the middle of a road: reuse the closest corner seen before,
     * otherwise remember this one. This is what makes repeated walks aggregate
     * even without any map data.
     */
    private fun clusterId(at: LatLon, now: Long): Long =
        repository.geoNodeNear(at, CLUSTER_RADIUS_METERS, now)?.id
            ?: repository.insertGeoNode(at, now)

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val CLUSTER_RADIUS_METERS = 25.0
        private const val MIN_PIECE_METERS = 3.0
    }
}
