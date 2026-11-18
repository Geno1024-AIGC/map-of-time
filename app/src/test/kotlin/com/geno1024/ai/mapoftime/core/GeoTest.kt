package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun haversineMatchesKnownDegreeLengths() {
        val d = Geo.distanceMeters(LatLon(0.0, 0.0), LatLon(0.0, 1.0))
        assertEquals(111_195.0, d, 100.0)
        val lat = Geo.distanceMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0))
        assertTrue(lat in 110_500.0..111_700.0)
    }

    @Test
    fun bearingIsClockwiseFromNorth() {
        assertEquals(0.0, Geo.bearingDegrees(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 0.5)
        assertEquals(90.0, Geo.bearingDegrees(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 0.5)
        assertEquals(270.0, Geo.bearingDegrees(LatLon(0.0, 0.1), LatLon(0.0, 0.0)), 0.5)
    }

    @Test
    fun angleDifferenceWrapsAroundNorth() {
        assertEquals(20.0, Geo.angleDifference(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDifference(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleDifference(270.0, 0.0), 1e-9)
    }

    @Test
    fun distanceToSegmentProjectsPerpendicularFoot() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.001)
        val off = LatLon(0.0005, 0.0005)
        assertEquals(55.6, Geo.distanceToSegment(off, a, b), 2.0)
    }

    @Test
    fun distanceToSegmentClampsBeyondEnds() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.001)
        val beyond = LatLon(0.0, 0.002)
        val expected = Geo.distanceMeters(b, beyond)
        assertEquals(expected, Geo.distanceToSegment(beyond, a, b), 1.0)
    }

    @Test
    fun segmentFractionIsClamped() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.001)
        assertEquals(0.5, Geo.segmentFraction(LatLon(0.0, 0.0005), a, b), 1e-3)
        assertEquals(0.0, Geo.segmentFraction(LatLon(0.0, -0.001), a, b), 1e-9)
        assertEquals(1.0, Geo.segmentFraction(LatLon(0.0, 0.002), a, b), 1e-9)
    }

    @Test
    fun pathLengthIgnoresStandingStill() {
        val points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001), LatLon(0.0, 0.00100001))
        val length = Geo.pathLengthMeters(points, minStep = 5.0)
        assertEquals(111.2, length, 2.0)
    }

    @Test
    fun mercatorRoundTrips() {
        val lat = 39.9042
        assertEquals(lat, Geo.mercatorLat(Geo.mercatorY(lat)), 1e-9)
    }

    @Test
    fun boundsExpandByMetres() {
        val b = Bounds(40.0, 116.0, 40.001, 116.001).expanded(100.0)
        assertTrue(b.minLat < 40.0 && b.maxLon > 116.001)
        assertTrue(b.contains(LatLon(40.0005, 116.0005)))
    }
}
