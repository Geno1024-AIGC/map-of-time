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
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.SettingsSnapshot
import com.geno1024.ai.mapoftime.data.TripStatus
import com.geno1024.ai.mapoftime.data.edgeEndpointPairLabel
import com.geno1024.ai.mapoftime.data.pointPairName
import com.geno1024.ai.mapoftime.osm.JunctionSplitter
import com.geno1024.ai.mapoftime.osm.MatchedPiece
import com.geno1024.ai.mapoftime.osm.OsmSignal
import com.geno1024.ai.mapoftime.osm.RoadAccess
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
        // A trip restored from an export has legs but no raw fixes. Running the
        // segmenter over nothing would replace those legs with an empty list
        // and quietly delete the samples the user just imported.
        if (raw.size < 2) return null
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
        // Read once: naming a point is rare, and a per-leg query would repeat
        // the same few rows for every piece of every trip. `pointLabels` is
        // the effective name, so a junction's name already shows here even
        // though this trip has never seen the point before.
        val pointNames = repository.pointLabels()

        for (leg in segmentation.legs) {
            val pieces = JunctionSplitter.split(
                points = segmentation.points,
                leg = leg,
                graph = graph,
                snapMeters = config.junctionSnapMeters.toDouble(),
                minPieceMeters = trip.mode.profile.minLegForSplitMeters,
                traveler = RoadAccess.of(trip.mode),
            )
            for (piece in pieces) {
                val analyzed = toLeg(piece, segmentation.points, speeds, trip.mode.id, graph, signals, pointNames, now) ?: continue
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
        // A corner that just turned up may belong to a junction the user
        // already named; it inherits that name instead of waiting to be named.
        repository.adoptNewEndpoints()
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
        pointNames: Map<String, String>,
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

        val fromKind: String
        val fromNode: Long
        val toKind: String
        val toNode: Long
        val key: String
        val identity: MatchKind
        val partial: Boolean
        when {
            fullyBounded -> {
                fromKind = PointRef.OSM
                fromNode = startJunction
                toKind = PointRef.OSM
                toNode = endJunction
                key = SegmentKeys.osm(startJunction, endJunction)
                identity = MatchKind.OSM
                partial = false
            }
            graph != null -> {
                fromKind = if (startJunction != null) PointRef.OSM else PointRef.GEO
                fromNode = startJunction ?: clusterId(piece.start.at, now)
                toKind = if (endJunction != null) PointRef.OSM else PointRef.GEO
                toNode = endJunction ?: clusterId(piece.end.at, now)
                key = SegmentKeys.mixed(fromNode, toNode)
                identity = MatchKind.PARTIAL
                partial = true
            }
            else -> {
                fromKind = PointRef.GEO
                fromNode = clusterId(piece.start.at, now)
                toKind = PointRef.GEO
                toNode = clusterId(piece.end.at, now)
                key = SegmentKeys.geo(fromNode, toNode)
                identity = MatchKind.GEOMETRIC
                partial = false
            }
        }
        val fromKey = PointRef(fromKind, fromNode).key
        val toKey = PointRef(toKind, toNode).key

        val existing = repository.edgeByKey(key, mode)
        val direction = when {
            existing == null -> 1
            Geo.distanceMeters(geometry.first(), existing.from) <=
                Geo.distanceMeters(geometry.first(), existing.to) -> 1
            else -> -1
        }
        val edgeId = repository.insertEdge(
            key = key,
            mode = mode,
            name = piece.label,
            identity = identity,
            from = geometry.first(),
            to = geometry.last(),
            lengthM = meters,
            seenAt = now,
            fromKey = fromKey,
            toKey = toKey,
        )

        return LegResult(
            leg = PendingLeg(
                startMs = startMs,
                endMs = endMs,
                distanceM = meters,
                edgeId = edgeId,
                direction = direction,
                label = existing?.preferredLabel
                    ?: edgeEndpointPairLabel(fromKey, pointNames[fromKey], toKey, pointNames[toKey])
                    ?: piece.label,
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
