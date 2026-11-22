package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointDetailsTest {
    /** One degree of latitude is ~111 km; one metre of longitude is wider. */
    private val metre = 1.0 / 111_320.0
    private val centre = LatLon(31.2, 121.4)

    private fun at(eastM: Double, northM: Double): LatLon = LatLon(
        centre.lat + northM * metre,
        centre.lon + eastM * metre / 0.855,
    )

    private fun road(name: String?): Edge = Edge(
        id = 1,
        key = "osm:1-osm:2",
        mode = TripMode.DRIVE,
        name = name,
        identity = MatchKind.OSM,
        aLat = centre.lat,
        aLon = centre.lon,
        bLat = centre.lat,
        bLon = centre.lon,
        lengthM = 30.0,
        firstSeen = 0,
        lastSeen = 0,
        fromKey = "osm:1",
        toKey = "osm:2",
    )

    @Test
    fun aPointOnTheNorthSideIsSaidSo() {
        assertEquals("北侧", PointDetails.of(at(0.0, 100.0), centre).bearing)
        assertEquals("南侧", PointDetails.of(at(0.0, -100.0), centre).bearing)
        assertEquals("东侧", PointDetails.of(at(100.0, 0.0), centre).bearing)
    }

    @Test
    fun aCornerGetsTheDiagonalSector() {
        assertEquals("东南侧", PointDetails.of(at(100.0, -100.0), centre).bearing)
        assertEquals("西北侧", PointDetails.of(at(-100.0, 100.0), centre).bearing)
    }

    @Test
    fun aPointAtTheCentreHasNoSide() {
        assertNull(PointDetails.of(centre, centre).bearing)
    }

    @Test
    fun roadsAreDistinctAndDropTheJunctionName() {
        val detail = PointDetails.of(
            at(0.0, 100.0),
            centre,
            placeName = "人民广场",
            touching = listOf(
                road("建国路"),
                road("人民路"),
                road("建国路"),
                road("人民广场"),
                road(null),
                road("  "),
            ),
        )
        assertEquals(listOf("建国路", "人民路"), detail.roads)
    }

    @Test
    fun theCaptionJoinsBearingAndRoadsWithoutNamingWork() {
        val detail = PointDetails.of(
            at(0.0, 100.0),
            centre,
            touching = listOf(road("人民路"), road("建国路")),
        )
        assertEquals("北侧 · 人民路 × 建国路", detail.label)
    }

    @Test
    fun aPointWithNothingToSayHasNoCaption() {
        assertNull(PointDetails.of(centre, centre).label)
    }

    @Test
    fun aRoadTheUserNamedCountsEvenWithoutOsm() {
        val detail = PointDetails.of(
            at(0.0, 100.0),
            centre,
            touching = listOf(userRoad("新城路"), userRoad("工业东二路")),
        )
        assertEquals("北侧 · 新城路 × 工业东二路", detail.label)
    }

    @Test
    fun aUserNameBeatsTheOsmName() {
        val detail = PointDetails.of(centre, centre, touching = listOf(road("OSM").copy(userName = "用户命名的路")))
        assertEquals(listOf("用户命名的路"), detail.roads)
    }

    @Test
    fun explicitRoadsAreAuthoritativeOverDerivedOnes() {
        // Roads the user confirmed at the point win over whatever the segments
        // say, and duplicates collapse into one crossing caption.
        val detail = PointDetails.of(
            at(0.0, 100.0),
            centre,
            placeName = "人民广场",
            touching = listOf(road("建国路"), road("人民路")),
            explicitRoads = listOf("科创路", "学府路", "科创路"),
        )
        assertEquals(listOf("科创路", "学府路"), detail.roads)
        assertEquals("北侧 · 科创路 × 学府路", detail.label)
    }

    @Test
    fun aFourWayCrossingListsEveryRoad() {
        val detail = PointDetails.of(
            centre,
            centre,
            touching = listOf(
                userRoad("人民路"), userRoad("建国路"), userRoad("解放路"), userRoad("中山路"),
            ),
        )
        assertEquals("人民路 × 建国路 × 解放路 × 中山路", detail.label)
    }

    private fun userRoad(name: String): Edge = road(null).copy(name = null, userName = name)
}
