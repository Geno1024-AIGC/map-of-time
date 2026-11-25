package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.MatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeGeometryTest {
    private val from = LatLon(39.9, 116.4)
    private val to = LatLon(39.9, 116.402)
    private val edge = Edge(
        id = 1,
        key = "osm:1-osm:2",
        mode = com.geno1024.ai.mapoftime.core.TripMode.WALK,
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

    private val bend1 = LatLon(39.9003, 116.4005)
    private val bend2 = LatLon(39.9006, 116.4014)
    private val curved = listOf(from, bend1, bend2, to)

    @Test
    fun keepsTheRecordedBendAndSnapsBothEnds() {
        val shape = EdgeGeometry.displayShape(edge, Polyline.encode(curved), partial = false)

        assertEquals(from, shape.first())
        assertEquals(to, shape.last())
        assertTrue(bend1 in shape)
        assertTrue(bend2 in shape)
    }

    @Test
    fun orientsABackwardRecordingFromTheEdgeStart() {
        val shape = EdgeGeometry.displayShape(
            edge,
            Polyline.encode(curved.asReversed()),
            partial = false,
        )

        assertEquals(from, shape.first())
        assertEquals(to, shape.last())
        assertEquals(EdgeGeometry.displayShape(edge, Polyline.encode(curved), false), shape)
    }

    @Test
    fun rejectsPartialAndOffEndTraces() {
        assertTrue(EdgeGeometry.displayShape(edge, Polyline.encode(curved), partial = true).isEmpty())
        val somewhereElse = listOf(LatLon(39.901, 116.4), bend1, bend2, LatLon(39.901, 116.402))
        assertTrue(
            EdgeGeometry.displayShape(edge, Polyline.encode(somewhereElse), partial = false).isEmpty(),
        )
    }

    @Test
    fun dropsFixJitterButKeepsTheCurve() {
        val jittery = buildList {
            add(from)
            repeat(24) { add(LatLon(39.9, 116.4 + (it + 1) * 0.00001)) }
            add(to)
        }
        val shape = EdgeGeometry.displayShape(edge, Polyline.encode(jittery), partial = false)

        assertEquals(from, shape.first())
        assertEquals(to, shape.last())
        assertTrue(shape.size < jittery.size)
    }
}
