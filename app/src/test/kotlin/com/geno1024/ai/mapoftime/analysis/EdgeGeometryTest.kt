package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.EdgeShapeRow
import com.geno1024.ai.mapoftime.data.MatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeGeometryTest {
    private val from = LatLon(39.9, 116.4)
    private val to = LatLon(39.9, 116.402)
    private val edge = edge(from, to)

    private fun edge(from: LatLon, to: LatLon) = Edge(
        id = 1,
        key = "osm:1-osm:2",
        mode = TripMode.WALK,
        name = null,
        identity = MatchKind.OSM,
        aLat = from.lat,
        aLon = from.lon,
        bLat = to.lat,
        bLon = to.lon,
        lengthM = 170.0,
        firstSeen = 0,
        lastSeen = 0,
    )

    private fun candidate(points: List<LatLon>, partial: Boolean = false) =
        EdgeShapeRow(Polyline.encode(points), partial)

    private val bend1 = LatLon(39.9003, 116.4005)
    private val bend2 = LatLon(39.9006, 116.4014)
    private val curved = listOf(from, bend1, bend2, to)

    @Test
    fun keepsTheRecordedBendAndSnapsBothEnds() {
        val shape = EdgeGeometry.displayShape(edge, listOf(candidate(curved)))

        assertEquals(from, shape.first())
        assertEquals(to, shape.last())
        assertTrue(bend1 in shape)
        assertTrue(bend2 in shape)
    }

    @Test
    fun orientsABackwardRecordingFromTheEdgeStart() {
        val shape = EdgeGeometry.displayShape(edge, listOf(candidate(curved.asReversed())))

        assertEquals(from, shape.first())
        assertEquals(to, shape.last())
        assertEquals(EdgeGeometry.displayShape(edge, listOf(candidate(curved))), shape)
    }

    @Test
    fun rejectsPartialAndOffEndTracesButUsesTheNextCandidate() {
        val elsewhere = listOf(LatLon(39.901, 116.4), bend1, bend2, LatLon(39.901, 116.402))
        assertTrue(EdgeGeometry.displayShape(edge, listOf(candidate(curved, partial = true))).isEmpty())
        assertTrue(EdgeGeometry.displayShape(edge, listOf(candidate(elsewhere))).isEmpty())

        val shape = EdgeGeometry.displayShape(
            edge,
            listOf(candidate(elsewhere), candidate(curved)),
        )
        assertEquals(from, shape.first())
        assertTrue(bend1 in shape)
    }

    @Test
    fun prefersTheShapeClosestToTheOtherRecordings() {
        val start = LatLon(39.9, 116.4)
        val end = LatLon(39.9, 116.403)
        val typicalA = listOf(start, LatLon(39.9004, 116.401), LatLon(39.9002, 116.402), end)
        val typicalB = listOf(start, LatLon(39.9005, 116.4011), LatLon(39.9003, 116.4021), end)
        val outlier = listOf(start, LatLon(39.9015, 116.401), LatLon(39.8985, 116.402), end)

        val shape = EdgeGeometry.displayShape(
            edge(start, end),
            listOf(candidate(outlier), candidate(typicalA), candidate(typicalB)),
        )

        assertTrue(shape.any { it.lat in 39.9001..39.901 })
    }

    @Test
    fun keepsCornersAndDropsFixJitter() {
        val corner = LatLon(39.9004, 116.401)
        val jittery = buildList {
            add(from)
            repeat(12) { add(LatLon(39.9, 116.4 + (it + 1) * 0.00001)) }
            add(corner)
            repeat(12) { add(LatLon(39.9004, 116.401 + (it + 1) * 0.00001)) }
            add(to)
        }

        val shape = EdgeGeometry.displayShape(edge, listOf(candidate(jittery)))

        assertEquals(from, shape.first())
        assertEquals(to, shape.last())
        assertTrue(corner in shape)
        assertTrue(shape.size < jittery.size)
    }
}
