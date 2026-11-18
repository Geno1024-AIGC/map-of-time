package com.geno1024.ai.mapoftime.core

import kotlin.math.cos
import kotlin.math.sin

/** Builds synthetic traces so the segmenter can be tested without a phone. */
class TrackBuilder(
    private val start: LatLon = LatLon(39.9, 116.3),
    private val startTime: Long = 1_700_000_000_000L,
) {
    private val points = mutableListOf<TrackPoint>()
    private var here = start
    private var time = startTime

    fun straight(
        meters: Double,
        speedMps: Double = 1.4,
        bearingDegrees: Double = 0.0,
        accuracy: Double = 5.0,
    ): TrackBuilder {
        val steps = (meters / speedMps).toInt().coerceAtLeast(1)
        val stepMeters = meters / steps
        for (i in 0 until steps) {
            here = offset(here, stepMeters, bearingDegrees)
            time += 1_000
            points += TrackPoint(time, here, accuracy)
        }
        return this
    }

    /** Stands still, emitting one fix per second. */
    fun standStill(seconds: Int, accuracy: Double = 5.0): TrackBuilder {
        repeat(seconds) {
            time += 1_000
            points += TrackPoint(time, here, accuracy)
        }
        return this
    }

    /** Moves without emitting fixes, so the next fix jumps in both space and time. */
    fun teleport(meters: Double, bearingDegrees: Double = 0.0, seconds: Int = 120): TrackBuilder {
        here = offset(here, meters, bearingDegrees)
        time += seconds * 1_000L
        return this
    }

    fun duplicateLast(accuracy: Double = 5.0): TrackBuilder {
        points += TrackPoint(time, here, accuracy)
        return this
    }

    fun lastTime(): Long = time

    fun build(): List<TrackPoint> = points.toList()

    private fun offset(from: LatLon, meters: Double, bearingDegrees: Double): LatLon {
        val rad = Math.toRadians(bearingDegrees)
        val dLat = meters * cos(rad) / 110_540.0
        val dLon = meters * sin(rad) / (111_320.0 * cos(Math.toRadians(from.lat)))
        return LatLon(from.lat + dLat, from.lon + dLon)
    }
}

object TrackMath {
    fun meters(points: List<TrackPoint>, slice: TrackSlice): Double =
        Geo.pathLengthMeters(points.subList(slice.from, slice.to).map { it.at }, minStep = 0.5)

    fun durationMs(points: List<TrackPoint>, slice: TrackSlice): Long =
        points[slice.to - 1].t - points[slice.from].t
}
