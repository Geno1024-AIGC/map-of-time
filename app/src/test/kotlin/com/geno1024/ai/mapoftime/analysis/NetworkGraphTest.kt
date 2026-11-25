package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TraversalSample
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGraphTest {
    private var nextId = 1L

    private fun summary(
        fromKey: String?,
        toKey: String?,
        from: LatLon = LatLon(0.0, 0.0),
        to: LatLon = LatLon(0.0, 0.001),
        fromName: String? = null,
        toName: String? = null,
        forwardMs: Long = 60_000,
        backwardMs: Long = 0,
    ): EdgeSummary {
        val samples = buildList {
            if (forwardMs > 0) add(TraversalSample(forwardMs, 100.0, 1, 0))
            if (backwardMs > 0) add(TraversalSample(backwardMs, 100.0, -1, 1_000))
        }
        return EdgeSummary(
            edge = Edge(
                id = nextId++,
                key = "${fromKey ?: from}-${toKey ?: to}",
                mode = TripMode.WALK,
                name = null,
                identity = MatchKind.OSM,
                aLat = from.lat,
                aLon = from.lon,
                bLat = to.lat,
                bLon = to.lon,
                lengthM = 100.0,
                firstSeen = 0,
                lastSeen = 0,
                fromKey = fromKey,
                toKey = toKey,
                fromName = fromName,
                toName = toName,
            ),
            combined = DurationStats.of(samples),
            forward = DurationStats.of(samples.filter { it.direction > 0 }),
            backward = DurationStats.of(samples.filter { it.direction < 0 }),
        )
    }

    @Test
    fun segmentsSharingAnEndBecomeOneNetwork() {
        val graph = NetworkGraphs.build(
            listOf(
                summary("osm:1", "osm:2"),
                summary("osm:2", "osm:3"),
            ),
        )

        assertEquals(3, graph.nodes.size)
        assertEquals(1, graph.parts)
        assertEquals(2, graph.node("osm:2")?.degree)
        assertEquals(1, graph.node("osm:1")?.degree)
    }

    @Test
    fun separatedGroupsAreCountedAsParts() {
        val graph = NetworkGraphs.build(
            listOf(
                summary("osm:1", "osm:2"),
                summary("osm:8", "osm:9"),
            ),
        )

        assertEquals(4, graph.nodes.size)
        assertEquals(2, graph.parts)
    }

    @Test
    fun aLinkKnowsWhichWayLeavesTheJunction() {
        val oneWay = summary("osm:1", "osm:2", forwardMs = 45_000)
        val graph = NetworkGraphs.build(listOf(oneWay))

        val fromNode = graph.node("osm:1")!!
        val fromLink = fromNode.links.single()
        assertEquals(true, fromLink.fromHere)
        assertEquals(45_000, fromLink.outbound.medianMs)
        assertEquals(0, fromLink.inbound.count)

        // From the other end the same samples are the way back, not the way out.
        val toLink = graph.node("osm:2")!!.links.single()
        assertEquals(false, toLink.fromHere)
        assertEquals(0, toLink.outbound.count)
        assertEquals(45_000, toLink.inbound.medianMs)
        assertEquals("osm:1", toLink.otherKey)
    }

    @Test
    fun endpointsWithoutAKeyStillMeetAtTheSameCoordinates() {
        val corner = LatLon(31.2, 121.4)
        val graph = NetworkGraphs.build(
            listOf(
                summary(null, null, from = LatLon(31.1, 121.4), to = corner),
                summary(null, null, from = corner, to = LatLon(31.3, 121.4)),
            ),
        )

        assertEquals(3, graph.nodes.size)
        assertEquals(1, graph.parts)
        val shared = graph.nodes.first { it.degree == 2 }
        assertEquals(corner, shared.at)
        assertNotNull(shared.key)
    }

    @Test
    fun routeUsesTheCheaperConnectionNotTheFirstOne() {
        val graph = NetworkGraphs.build(
            listOf(
                summary("osm:1", "osm:2", forwardMs = 120_000, backwardMs = 120_000),
                summary("osm:2", "osm:3", forwardMs = 30_000, backwardMs = 30_000),
                summary("osm:1", "osm:3", forwardMs = 45_000, backwardMs = 45_000),
            ),
        )

        val route = graph.route("osm:1", "osm:3")!!
        assertEquals(listOf("osm:1", "osm:3"), route.nodeKeys)
        assertEquals(45_000L, route.typicalMs)
        assertEquals(0, route.estimatedHops)
    }

    @Test
    fun routeSumsDirectionalMediansAndMarksGuessedDirections() {
        val graph = NetworkGraphs.build(
            listOf(
                summary("osm:1", "osm:2", forwardMs = 20_000, backwardMs = 50_000),
                summary("osm:2", "osm:3", forwardMs = 0, backwardMs = 40_000),
            ),
        )

        // Leaving osm:1 uses the 20 s forward sample; leaving osm:2 for
        // osm:3 has no forward sample, so the 40 s reverse one stands in.
        val there = graph.route("osm:1", "osm:3")!!
        assertEquals(60_000L, there.typicalMs)
        assertEquals(1, there.estimatedHops)

        // The way back has a sample in each direction actually travelled.
        val back = graph.route("osm:3", "osm:1")!!
        assertEquals(90_000L, back.typicalMs)
        assertEquals(0, back.estimatedHops)
    }

    @Test
    fun disconnectedNodesHaveNoRoute() {
        val graph = NetworkGraphs.build(
            listOf(summary("osm:1", "osm:2"), summary("osm:8", "osm:9")),
        )
        assertNull(graph.route("osm:1", "osm:9"))
        assertTrue(graph.route("osm:1", "osm:1")!!.hops.isEmpty())
    }

    @Test
    fun pointNamesAreCarriedOntoTheNodes() {
        val graph = NetworkGraphs.build(
            listOf(
                summary("osm:1", "osm:2", fromName = "north gate"),
                summary("osm:2", "osm:3", toName = "metro"),
            ),
        )

        assertEquals("north gate", graph.node("osm:1")?.name)
        assertEquals("metro", graph.node("osm:3")?.name)
    }
}
