package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SegmenterTest {
    private val walk = TripMode.WALK.profile

    @Test
    fun oneStraightWalkIsOneLeg() {
        val track = TrackBuilder().straight(200.0).build()
        val result = Segmenter.segment(track, walk)

        assertEquals(1, result.legs.size)
        assertEquals(200.0, TrackMath.meters(result.points, result.legs[0]), 3.0)
        assertEquals(142_000.0, TrackMath.durationMs(result.points, result.legs[0]).toDouble(), 2_000.0)
    }

    @Test
    fun shortStopStaysInsideTheLeg() {
        val track = TrackBuilder().straight(100.0).standStill(20).straight(100.0).build()
        val result = Segmenter.segment(track, walk)

        assertEquals(1, result.legs.size)
        assertEquals(0, result.pauses.size)
        val duration = TrackMath.durationMs(result.points, result.legs[0])
        assertTrue("duration was $duration", duration > 160_000L)
    }

    @Test
    fun longStopBreaksTheTripAndIsNotCounted() {
        val track = TrackBuilder().straight(100.0).standStill(200).straight(100.0).build()
        val result = Segmenter.segment(track, walk)

        assertEquals(2, result.legs.size)
        assertEquals(1, result.pauses.size)
        result.legs.forEach { slice ->
            val duration = TrackMath.durationMs(result.points, slice)
            assertTrue("leg absorbed the standstill: $duration", duration < 100_000L)
        }
    }

    @Test
    fun rightAngleTurnSplitsTheLeg() {
        val track = TrackBuilder().straight(100.0).straight(100.0, bearingDegrees = 90.0).build()
        val result = Segmenter.segment(track, walk)

        assertEquals(2, result.legs.size)
        assertTrue(TrackMath.meters(result.points, result.legs[0]) in 70.0..130.0)
        assertTrue(TrackMath.meters(result.points, result.legs[1]) in 70.0..130.0)
    }

    @Test
    fun aRoundedCornerSplitsEvenThoughNoSinglePointTurnsSixtyFiveDegrees() {
        // A 90 degree corner with a 10 m radius: the heading changes ~5.6 deg
        // per metre, so "8 m before vs 8 m after" never reaches 65 degrees.
        val builder = TrackBuilder().straight(100.0)
        for (i in 1..16) builder.straight(1.0, bearingDegrees = i * 90.0 / 16.0)
        val track = builder.straight(100.0, bearingDegrees = 90.0).build()

        val result = Segmenter.segment(track, walk)

        assertEquals(2, result.legs.size)
        assertTrue(TrackMath.meters(result.points, result.legs[0]) in 90.0..130.0)
        assertTrue(TrackMath.meters(result.points, result.legs[1]) in 90.0..130.0)
    }

    @Test
    fun aDiagonalCrossingStillSplits() {
        // Crossing a road at 45 degrees changes heading in two 45 degree
        // steps; each step alone is below the walking threshold.
        val builder = TrackBuilder().straight(100.0)
        builder.straight(15.0, bearingDegrees = 45.0)
        val track = builder.straight(100.0, bearingDegrees = 90.0).build()

        val result = Segmenter.segment(track, walk)

        assertEquals(2, result.legs.size)
    }

    @Test
    fun stoppingAtTheCornerDoesNotHideTheTurn() {
        // Standing at the light puts the approach and the departure either
        // side of a standstill; the turn is still the same road change.
        val track = TrackBuilder()
            .straight(100.0)
            .standStill(30)
            .straight(100.0, bearingDegrees = 90.0)
            .build()

        val result = Segmenter.segment(track, walk)

        assertEquals(2, result.legs.size)
    }

    @Test
    fun aLongSweepingCurveIsStillOneRoad() {
        // 90 degrees spread over 300 m is a boulevard bend, not a junction.
        val builder = TrackBuilder()
        for (i in 1..300) builder.straight(1.0, bearingDegrees = i * 90.0 / 300.0)

        val result = Segmenter.segment(builder.build(), walk)

        assertEquals(1, result.legs.size)
    }

    @Test
    fun manualMarkSplitsEvenOnAStraightRoad() {
        val builder = TrackBuilder().straight(100.0)
        val mark = builder.lastTime()
        val track = builder.straight(100.0).build()

        val result = Segmenter.segment(track, walk, manualMarks = listOf(mark))
        assertEquals(2, result.legs.size)
    }

    @Test
    fun gpsJumpIsDroppedInsteadOfBecomingSpeed() {
        val builder = TrackBuilder().straight(100.0)
        builder.teleport(800.0, seconds = 1)
        val track = builder.straight(100.0).build()

        val result = Segmenter.segment(track, walk)
        val total = result.legs.sumOf { TrackMath.meters(result.points, it) }
        assertEquals(2, result.legs.size)
        assertTrue("total distance was $total", total < 260.0)
    }

    @Test
    fun gapInFixesNeverBridgesTheJourney() {
        val builder = TrackBuilder().straight(100.0)
        builder.teleport(100.0, seconds = 180)
        val track = builder.straight(100.0).build()

        val result = Segmenter.segment(track, walk)
        assertEquals(2, result.legs.size)
    }

    @Test
    fun poorFixesAreDropped() {
        val track = TrackBuilder().straight(100.0).straight(100.0, accuracy = 120.0).build()
        val result = Segmenter.segment(track, walk)

        assertTrue(result.droppedPoints > 0)
        assertEquals(1, result.legs.size)
    }

    @Test
    fun repeatedFixesAtTheSameInstantAreDropped() {
        val track = TrackBuilder().straight(50.0).duplicateLast().straight(50.0).build()
        val result = Segmenter.segment(track, walk)
        assertTrue(result.droppedPoints >= 1)
        assertEquals(1, result.legs.size)
    }

    @Test
    fun emptyAndSingleFixTracesProduceNoLegs() {
        assertEquals(0, Segmenter.segment(emptyList(), walk).legs.size)
        val one = listOf(TrackPoint(1L, LatLon(1.0, 1.0)))
        assertEquals(0, Segmenter.segment(one, walk).legs.size)
    }

    @Test
    fun modeChangesWhatCountsAsATurn() {
        // 55°: a bend for a driver, a corner for a walker only above 65°.
        val track = TrackBuilder().straight(100.0).straight(100.0, bearingDegrees = 55.0).build()
        val walked = Segmenter.segment(track, TripMode.WALK.profile)
        val driven = Segmenter.segment(track, TripMode.DRIVE.profile)
        assertTrue(driven.legs.size >= walked.legs.size)
        assertTrue(abs(walked.legs.size - driven.legs.size) <= 1)
    }
}
