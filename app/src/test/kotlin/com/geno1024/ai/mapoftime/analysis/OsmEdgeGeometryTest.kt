package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.osm.OsmWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsmEdgeGeometryTest {
    private val a = LatLon(39.9, 116.4)
    private val b = LatLon(39.9, 116.401)
    private val c = LatLon(39.9005, 116.402)
    private val d = LatLon(39.9, 116.403)
    private val path = listOf(a, b, c, d)
    private val index = OsmPathIndex(
        listOf(
            OsmWay(1, "residential", null, null, false, listOf(1, 2, 3), path.take(3)),
            OsmWay(2, "residential", null, null, false, listOf(3, 4), path.drop(2)),
        ),
    )

    private fun edge(fromKey: String, toKey: String, lengthM: Double) = Edge(
        id = 1,
        key = "$fromKey-$toKey",
        mode = TripMode.WALK,
        name = null,
        identity = MatchKind.OSM,
        aLat = a.lat,
        aLon = a.lon,
        bLat = d.lat,
        bLon = d.lon,
        lengthM = lengthM,
        firstSeen = 0,
        lastSeen = 0,
        fromKey = fromKey,
        toKey = toKey,
    )

    @Test
    fun followsTheWayGeometryBetweenTwoOsmNodes() {
        val shape = OsmEdgeGeometry.shape(index, edge("osm:1", "osm:4", Geo.pathLengthMeters(path)))

        assertEquals(path, shape)
    }

    @Test
    fun fallsBackWhenAnEndIsOnlyALocalCorner() {
        assertNull(OsmEdgeGeometry.shape(index, edge("geo:1", "osm:4", 300.0)))
    }

    @Test
    fun fallsBackWhenTheCachedWaysDoNotConnect() {
        val elsewhere = OsmWay(3, "footway", null, null, false, listOf(5, 6), listOf(c, d))
        val isolated = OsmPathIndex(listOf(elsewhere))

        assertNull(OsmEdgeGeometry.shape(isolated, edge("osm:1", "osm:4", 300.0)))
    }

    @Test
    fun rejectsAPathFarLongerThanTheRecordedEdge() {
        assertNull(OsmEdgeGeometry.shape(index, edge("osm:1", "osm:4", 10.0)))
    }
}
