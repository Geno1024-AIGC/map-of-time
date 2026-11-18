package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    @Test
    fun matchesTheReferenceEncoding() {
        val points = listOf(
            LatLon(38.5, -120.2),
            LatLon(40.7, -120.95),
            LatLon(43.252, -126.453),
        )
        val encoded = Polyline.encode(points)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        assertEquals(points, Polyline.decode(encoded))
    }

    @Test
    fun emptyInputRoundTrips() {
        assertEquals("", Polyline.encode(emptyList()))
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test
    fun longTrackRoundTripsWithinFiveDecimals() {
        val points = (0 until 500).map { i ->
            LatLon(39.9 + i * 0.00013, 116.3 - i * 0.00007)
        }
        val decoded = Polyline.decode(Polyline.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-5)
            assertEquals(a.lon, b.lon, 1e-5)
        }
    }
}
