package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadRunsTest {
    private val metre = 1.0 / 111_320.0
    private val origin = LatLon(31.2, 121.4)

    private fun at(eastM: Double, northM: Double): LatLon = LatLon(
        origin.lat + northM * metre,
        origin.lon + eastM * metre / 0.855,
    )

    private fun segment(
        id: Long,
        from: String,
        to: String,
        a: LatLon,
        b: LatLon,
        userName: String? = null,
    ): Segment = Segment(
        id = id,
        key = "geo:$from-$to",
        mode = TripMode.DRIVE,
        name = null,
        identity = MatchKind.GEOMETRIC,
        aLat = a.lat,
        aLon = a.lon,
        bLat = b.lat,
        bLon = b.lon,
        lengthM = 100.0,
        firstSeen = 0,
        lastSeen = 0,
        fromKey = from,
        toKey = to,
        userName = userName,
    )

    private val first = segment(1, "geo:0", "geo:1", at(0.0, 0.0), at(100.0, 0.0))
    private val second = segment(2, "geo:1", "geo:2", at(100.0, 0.0), at(200.0, 0.0))
    private val third = segment(3, "geo:2", "geo:3", at(200.0, 0.0), at(300.0, 0.0))

    @Test
    fun aStraightStretchIsNamedInOneGo() {
        val run = RoadRuns.contiguous(listOf(third, first, second), 2)

        assertEquals(listOf(1L, 2L, 3L), run.map { it.id })
    }

    @Test
    fun aTurnStartsADifferentRoad() {
        val arm = segment(4, "geo:2", "geo:4", at(200.0, 0.0), at(200.0, 100.0))
        val run = RoadRuns.contiguous(listOf(first, second, third, arm), 2)

        assertEquals(listOf(1L, 2L, 3L), run.map { it.id })
    }

    @Test
    fun aNeighbourWithItsOwnRoadNameIsLeftAlone() {
        val named = third.copy(userName = "工业东二路")
        val run = RoadRuns.contiguous(listOf(first, second, named), 2)

        assertEquals(listOf(1L, 2L), run.map { it.id })
    }

    @Test
    fun aGentleCurveStaysInTheRunButABranchDoesNot() {
        // 20° off straight is still this road; 60° off is a junction arm.
        val curve = segment(4, "geo:3", "geo:4", at(300.0, 0.0), at(394.0, 34.0))
        val arm = segment(5, "geo:3", "geo:5", at(300.0, 0.0), at(350.0, 86.0))
        val run = RoadRuns.contiguous(listOf(second, third, curve, arm), 3)

        assertEquals(listOf(2L, 3L, 4L), run.map { it.id })
    }

    @Test
    fun aSegmentWithNoNeighboursIsItsOwnRun() {
        assertEquals(listOf(2L), RoadRuns.contiguous(listOf(second), 2).map { it.id })
    }
}
