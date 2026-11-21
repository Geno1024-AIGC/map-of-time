package com.geno1024.ai.mapoftime.osm

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackBuilder
import com.geno1024.ai.mapoftime.core.TrackSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

object OsmFixture {
    /**
     * A 3 x 4 lattice of streets, ~110 m apart. Node ids encode the grid
     * position: `100 * latIndex + lonIndex`.
     */
    fun gridGraph(): RoadGraph {
        val lat = { i: Int -> 0.001 * i }
        val lon = { i: Int -> 0.001 * i }
        val id = { i: Int, j: Int -> 100L * i + j }
        val ways = mutableListOf<OsmWay>()

        var wayId = 1_000L
        for (i in 0..2) {
            ways += OsmWay(
                id = wayId++,
                highway = "residential",
                name = "Horizontal ${'A' + i}",
                ref = null,
                oneway = false,
                nodeIds = (0..3).map { j -> id(i, j) },
                points = (0..3).map { j -> LatLon(lat(i), lon(j)) },
            )
        }
        for (j in 0..3) {
            ways += OsmWay(
                id = wayId++,
                highway = "residential",
                name = "Vertical ${'A' + j}",
                ref = null,
                oneway = false,
                nodeIds = (0..2).map { i -> id(i, j) },
                points = (0..2).map { i -> LatLon(lat(i), lon(j)) },
            )
        }
        return RoadGraph(ways)
    }

    /**
     * A road 1-2-3-4 with a zebra footway 10-2-11 across it. At node 2 a walker
     * sees four arms (the road both ways and the footway both ways) but a car
     * only sees the two road arms, so it is not a vehicle junction.
     */
    fun zebraGraph(): RoadGraph {
        val at = { lat: Double, lon: Double -> LatLon(lat, lon) }
        return RoadGraph(
            listOf(
                OsmWay(
                    1, "residential", "Main Road", null, false,
                    listOf(1L, 2L, 3L, 4L),
                    listOf(at(0.0, 0.0), at(0.0, 0.001), at(0.0, 0.002), at(0.0, 0.003)),
                ),
                OsmWay(
                    2, "footway", "Zebra", null, false,
                    listOf(10L, 2L, 11L),
                    listOf(at(0.001, 0.001), at(0.0, 0.001), at(-0.001, 0.001)),
                ),
            ),
        )
    }
}

class RoadGraphTest {
    private val graph = OsmFixture.gridGraph()

    @Test
    fun intersectionsAreNodesWithThreeOrMoreArms() {
        // Corners have two arms and must not be treated as crossings.
        assertNull(graph.nearestJunction(LatLon(0.0, 0.0), 20.0))
        assertNotNull(graph.nearestJunction(LatLon(0.001, 0.001), 20.0))
        assertTrue(graph.junctions.size >= 6)
    }

    @Test
    fun aNodeThatMerelySplitsOneWayIsNotAnIntersection() {
        val split = RoadGraph(
            listOf(
                OsmWay(1, "residential", "Long Road", null, false, listOf(10, 20), listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))),
                OsmWay(2, "residential", "Long Road", null, false, listOf(20, 30), listOf(LatLon(0.0, 0.001), LatLon(0.0, 0.002))),
            ),
        )
        assertTrue(split.junctions.isEmpty())
        assertNull(split.nearestJunction(LatLon(0.0, 0.001), 20.0))
    }

    @Test
    fun junctionsCarryTheirDegree() {
        val junction = graph.nearestJunction(LatLon(0.001, 0.001), 20.0)!!
        assertEquals(4, junction.degree)
        assertEquals(101L, junction.id)
    }

    @Test
    fun dominantLabelPicksTheRoadUnderfoot() {
        val label = graph.dominantLabel(listOf(LatLon(0.001, 0.0015), LatLon(0.001, 0.0017)))
        assertEquals("Horizontal B", label?.name)
        assertTrue(label!!.distanceMeters < 5.0)
    }

    @Test
    fun labelIsNullWhenFarFromAnyRoad() {
        assertNull(graph.dominantLabel(listOf(LatLon(0.02, 0.02))))
    }

    @Test
    fun duplicateWaysAreCollapsed() {
        val way = OsmWay(7, "residential", "Dup", null, false, listOf(1, 2), listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001)))
        assertEquals(1, RoadGraph(listOf(way, way)).ways.size)
    }

    @Test
    fun aZebraIsAWalkJunctionButNotAVehicleJunction() {
        val zebra = OsmFixture.zebraGraph()
        // Four walk arms at node 2: road in both directions and footway in both.
        assertNotNull(zebra.nearestJunction(LatLon(0.0, 0.001), 20.0, Traveler.WALK))
        assertTrue(zebra.junctions.any { it.id == 2L })
        // A car only sees the two road arms — degree 2 is not a junction.
        assertNull(zebra.nearestJunction(LatLon(0.0, 0.001), 20.0, Traveler.VEHICLE))
        assertTrue(zebra.vehicleJunctions.none { it.id == 2L })
    }
}

class JunctionSplitterTest {
    private val graph = OsmFixture.gridGraph()

    @Test
    fun noGraphMeansOnePartialPiece() {
        val track = TrackBuilder(LatLon(0.001, 0.0002), 0L).straight(100.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), null)
        assertEquals(1, pieces.size)
        assertTrue(!pieces[0].boundedByJunctions)
    }

    @Test
    fun walkIsCutAtEveryCrossing() {
        val track = TrackBuilder(LatLon(0.001, 0.0004), 0L).straight(250.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph)

        assertEquals(3, pieces.size)
        assertNull(pieces[0].start.junctionId)
        assertEquals(101L, pieces[0].end.junctionId)
        assertEquals(101L, pieces[1].start.junctionId)
        assertEquals(102L, pieces[1].end.junctionId)
        assertEquals(102L, pieces[2].start.junctionId)
        assertNull(pieces[2].end.junctionId)
        assertTrue(pieces[1].boundedByJunctions)
        assertTrue(!pieces[0].boundedByJunctions)
    }

    @Test
    fun completePieceIsADrivableUnitBetweenTwoCrossings() {
        val track = TrackBuilder(LatLon(0.001, 0.001), 0L).straight(170.0, bearingDegrees = 90.0).build()
        // Start exactly on the crossing, walk to the next one and a little past.
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph)
        val complete = pieces.first { it.boundedByJunctions }
        assertEquals(101L, complete.start.junctionId)
        assertEquals(102L, complete.end.junctionId)
        assertEquals("Horizontal B", complete.label)
        val meters = com.geno1024.ai.mapoftime.core.Geo.pathLengthMeters(
            track.subList(complete.slice.from, complete.slice.to).map { it.at },
        )
        assertTrue("complete piece was $meters m", meters in 90.0..130.0)
    }

    @Test
    fun eachPieceKeepsTheRoadNameUnderfoot() {
        val track = TrackBuilder(LatLon(0.001, 0.0002), 0L).straight(290.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph)
        assertEquals("Horizontal B", pieces[1].label)
    }

    @Test
    fun parallelRoadsDoNotInventBoundaries() {
        // Walk 25 m beside the crossing at (0.001, 0.001): close enough to snap,
        // but never approached, so it must not cut the leg.
        val track = TrackBuilder(LatLon(0.00125, 0.0002), 0L).straight(290.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph)
        assertEquals(1, pieces.size)
    }

    @Test
    fun shortNoiseAroundACrossingIsAbsorbed() {
        val track = TrackBuilder(LatLon(0.001, 0.00096), 0L).straight(10.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph, minPieceMeters = 8.0)
        assertEquals(1, pieces.size)
    }

    @Test
    fun piecesCoverTheLegWithoutLosingTime() {
        val track = TrackBuilder(LatLon(0.001, 0.0002), 0L).straight(290.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph)
        assertEquals(0, pieces.first().slice.from)
        assertEquals(track.size - 1, pieces.last().slice.to - 1)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.slice.to - 1, b.slice.from) }
        assertNotEquals(0, pieces.size)
    }

    @Test
    fun aVehicleDrivingOverAZebraIsNotCutThere() {
        val zebra = OsmFixture.zebraGraph()
        val track = TrackBuilder(LatLon(0.0, 0.0005), 0L).straight(200.0, bearingDegrees = 90.0).build()
        val walk = JunctionSplitter.split(track, TrackSlice(0, track.size), zebra, traveler = Traveler.WALK)
        assertEquals(2, walk.size)
        assertEquals(2L, walk[0].end.junctionId)
        val drive = JunctionSplitter.split(track, TrackSlice(0, track.size), zebra, traveler = Traveler.VEHICLE)
        assertEquals(1, drive.size)
        assertNull(drive[0].start.junctionId)
        assertNull(drive[0].end.junctionId)
    }

    @Test
    fun aVehicleIsStillCutAtARealJunction() {
        val track = TrackBuilder(LatLon(0.001, 0.0004), 0L).straight(250.0, bearingDegrees = 90.0).build()
        val pieces = JunctionSplitter.split(track, TrackSlice(0, track.size), graph, traveler = Traveler.VEHICLE)
        assertEquals(3, pieces.size)
        assertEquals(101L, pieces[0].end.junctionId)
        assertEquals(102L, pieces[1].end.junctionId)
    }
}
