package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.EdgeShapeRow
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.osm.OsmWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadShapesTest {
    private val a = LatLon(39.9, 116.4)
    private val bend = LatLon(39.9005, 116.401)
    private val c = LatLon(39.9, 116.402)
    private val osmPaths = OsmPathIndex(
        listOf(OsmWay(1, "residential", null, null, false, listOf(1, 2, 3), listOf(a, bend, c))),
    )

    private fun edge(id: Long, fromKey: String, toKey: String) = Edge(
        id = id,
        key = "$fromKey-$toKey",
        mode = TripMode.WALK,
        name = null,
        identity = MatchKind.OSM,
        aLat = a.lat,
        aLon = a.lon,
        bLat = c.lat,
        bLon = c.lon,
        lengthM = Geo.pathLengthMeters(listOf(a, bend, c)),
        firstSeen = 0,
        lastSeen = 0,
        fromKey = fromKey,
        toKey = toKey,
    )

    @Test
    fun prefersOsmGeometryAndUsesRecordedShapeForLocalCorners() {
        val localBend = LatLon(39.9001, 116.4015)
        val recorded = mapOf(
            1L to listOf(EdgeShapeRow(Polyline.encode(listOf(a, localBend, c)), false)),
            2L to listOf(EdgeShapeRow(Polyline.encode(listOf(a, localBend, c)), false)),
        )

        val shapes = RoadShapes.build(
            listOf(edge(1, "osm:1", "osm:3"), edge(2, "geo:1", "osm:3")),
            recorded,
            osmPaths,
        )

        assertEquals(listOf(a, bend, c), shapes[1L])
        assertEquals(listOf(a, localBend, c), shapes[2L])
    }

    @Test
    fun leavesAnEdgeWithNoUsableGeometryEmpty() {
        val shapes = RoadShapes.build(listOf(edge(3, "geo:1", "geo:3")), emptyMap(), osmPaths)

        assertTrue(shapes[3L].orEmpty().isEmpty())
    }
}
