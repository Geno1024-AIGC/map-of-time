package com.geno1024.ai.mapoftime.agg

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TripMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AggNetworkTest {
    private val a = LatLon(22.0, 114.0)
    private val b = LatLon(22.0005, 114.0005)

    private fun visit(at: LatLon, firstT: Long, count: Int = 8, accuracy: Double = 0.0) =
        (0 until count).map { TrackPoint(firstT + it * 1_000L, at, accuracy = accuracy) }

    @Test
    fun repeatedVisitsBecomePointsAndACrossing() {
        val network = Aggregator.aggregate(
            listOf(
                AggTrace(1, TripMode.WALK, visit(a, 0) + visit(b, 100_000)),
                AggTrace(2, TripMode.WALK, visit(b, 200_000) + visit(a, 300_000)),
            ),
        )

        assertEquals(2, network.points.size)
        assertEquals(0, network.unmatchedTrips)
        assertEquals(32, network.fixes)
        assertEquals(1, network.edges.size)
        val edge = network.edges.single()
        assertEquals(1, edge.fromId)
        assertEquals(2, edge.toId)
        assertEquals(2, edge.samples)
        assertEquals(93_000L, edge.typicalMs)
        assertTrue(edge.distanceM > 60.0)
    }

    @Test
    fun crossingsKeepTripDirectionTimeAndRecordedPath() {
        val network = Aggregator.aggregate(
            listOf(
                AggTrace(1, TripMode.WALK, visit(a, 0) + visit(b, 100_000)),
                AggTrace(2, TripMode.WALK, visit(b, 200_000) + visit(a, 300_000)),
            ),
        )

        val passes = network.edges.single().passes
        assertEquals(2, passes.size)
        assertTrue(passes.any {
            it.tripId == 1L && it.mode == TripMode.WALK && it.fromId == 1 && it.toId == 2 &&
                it.startedAt == 7_000L && it.endedAt == 100_000L &&
                it.durationMs == 93_000L && it.path.size == 2
        })
        assertTrue(passes.any { it.tripId == 2L && it.fromId == 2 && it.toId == 1 })
        // Newest first, so the history view can take the first row as "last time".
        assertEquals(2L, passes.first().tripId)
    }

    @Test
    fun oneVisitIsNotAPlace() {
        val network = Aggregator.aggregate(
            listOf(AggTrace(1, TripMode.WALK, visit(a, 0, count = 3))),
        )
        assertTrue(network.points.isEmpty())
        assertTrue(network.edges.isEmpty())
        assertEquals(1, network.unmatchedTrips)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val network = Aggregator.aggregate(
            listOf(AggTrace(1, TripMode.DRIVE, visit(a, 0, accuracy = 200.0))),
        )
        assertTrue(network.points.isEmpty())
        assertEquals(0, network.fixes)
    }

    @Test
    fun noTripsIsAnEmptyNetwork() {
        val network = Aggregator.aggregate(emptyList())
        assertTrue(network.points.isEmpty())
        assertTrue(network.edges.isEmpty())
        assertEquals(0, network.trips)
    }

    @Test
    fun modesDoNotShareCrossings() {
        val network = Aggregator.aggregate(
            listOf(
                AggTrace(1, TripMode.WALK, visit(a, 0) + visit(b, 100_000)),
                AggTrace(2, TripMode.DRIVE, visit(b, 200_000) + visit(a, 300_000)),
            ),
        )
        assertEquals(2, network.edges.size)
        assertEquals(setOf(TripMode.WALK, TripMode.DRIVE), network.edges.map { it.mode }.toSet())
    }

    @Test
    fun continuousTenKmTrackDoesNotBecomeOnePoint() {
        // Ten metres per fix for ten kilometres: adjacent cells touch all the
        // way along the road, which is exactly what used to union into one
        // city-wide "place" with a 10 km radius.
        val fixes = (0..1_000).map { i ->
            TrackPoint(i * 1_000L, LatLon(22.0 + i * 0.00009, 114.0))
        }
        val network = Aggregator.aggregate(listOf(AggTrace(1, TripMode.DRIVE, fixes)))

        assertTrue("expected several places, got ${network.points.size}", network.points.size > 5)
        assertTrue(
            "largest radius ${network.points.maxOf { it.radiusM }}",
            network.points.all { it.radiusM <= 60.001 },
        )
    }
}
