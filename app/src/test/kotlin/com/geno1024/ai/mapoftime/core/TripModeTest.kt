package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripModeTest {
    @Test
    fun fourModesHaveTheirOwnStoredId() {
        assertEquals(4, TripMode.entries.size)
        assertEquals(
            listOf("walk", "bike", "ebike", "drive"),
            TripMode.entries.map { it.id },
        )
    }

    @Test
    fun aStoredIdSurvivesARoundTrip() {
        TripMode.entries.forEach { mode ->
            assertEquals(mode, TripMode.of(mode.id))
        }
    }

    @Test
    fun anUnknownIdFallsBackToWalking() {
        assertEquals(TripMode.WALK, TripMode.of(null))
        assertEquals(TripMode.WALK, TripMode.of("hoverboard"))
    }

    @Test
    fun anEbikeIsFasterToFilterButStillSlowerThanACar() {
        val ebike = TripMode.EBIKE.profile
        val drive = TripMode.DRIVE.profile

        // Legal 25 km/h plus the usual margin for a derestricted one.
        assertTrue("maxSpeed ${ebike.maxSpeedMps}", ebike.maxSpeedMps >= 25.0 / 3.6)
        assertTrue(ebike.maxSpeedMps < drive.maxSpeedMps)
        assertTrue(ebike.turnDegrees > drive.turnDegrees)
        assertTrue(ebike.minLegMeters < drive.minLegMeters)
    }

    @Test
    fun anEbikeRideIsSegmentedAsOneLeg() {
        // 500 m at 30 km/h: above a bicycle's casual pace, well inside an
        // e-bike's, and no step may look like a teleport.
        val track = TrackBuilder().straight(500.0, speedMps = 8.3).build()
        val result = Segmenter.segment(track, TripMode.EBIKE.profile)

        assertEquals(1, result.legs.size)
        assertEquals(500.0, TrackMath.meters(result.points, result.legs[0]), 8.0)
    }
}
