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
}
