package com.geno1024.ai.mapoftime.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 coordinate. Kept deliberately tiny so it can travel through JSON. */
data class LatLon(val lat: Double, val lon: Double) {
    val isValid: Boolean
        get() = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing from [a] to [b], in degrees clockwise from north. */
    fun bearingDegrees(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest angle between two bearings, in `0..180`. */
    fun angleDifference(a: Double, b: Double): Double {
        val d = abs((a - b) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * Local flat projection around [origin]: metres east/north. Good to a few
     * hundred metres, which is all the segment matcher needs.
     */
    fun project(origin: LatLon, point: LatLon): Pair<Double, Double> {
        val x = (point.lon - origin.lon) * 111_320.0 * cos(Math.toRadians(origin.lat))
        val y = (point.lat - origin.lat) * 110_540.0
        return x to y
    }

    /** Distance from [point] to the segment [a]–[b], in metres. */
    fun distanceToSegment(point: LatLon, a: LatLon, b: LatLon): Double {
        val (px, py) = project(a, point)
        val (bx, by) = project(a, b)
        val lengthSq = bx * bx + by * by
        if (lengthSq < 1e-9) return sqrt(px * px + py * py)
        val t = ((px * bx + py * by) / lengthSq).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Fraction of the way from [a] to [b] that the closest point to [point]
     * lies, in `0..1`. Used to place a snapped junction exactly on the trace.
     */
    fun segmentFraction(point: LatLon, a: LatLon, b: LatLon): Double {
        val (px, py) = project(a, point)
        val (bx, by) = project(a, b)
        val lengthSq = bx * bx + by * by
        if (lengthSq < 1e-9) return 0.0
        return ((px * bx + py * by) / lengthSq).coerceIn(0.0, 1.0)
    }

    fun interpolate(a: LatLon, b: LatLon, fraction: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)

    /** Web-Mercator normalised Y in `0..1`, the projection raster tiles use. */
    fun mercatorY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        return (1.0 - kotlin.math.ln(kotlin.math.tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0
    }

    fun mercatorLat(y: Double): Double {
        val n = Math.PI * (1.0 - 2.0 * y)
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(n)))
    }

    /**
     * Total length of a polyline in metres. Points closer than [minStep] are
     * ignored so GPS jitter does not inflate a standstill into a distance.
     */
    fun pathLengthMeters(points: List<LatLon>, minStep: Double = 0.0): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        var anchor = points.first()
        for (i in 1 until points.size) {
            val d = distanceMeters(anchor, points[i])
            if (d >= minStep) {
                total += d
                anchor = points[i]
            }
        }
        return total
    }

    /** Axis-aligned bounding box; `null` for an empty input. */
    fun bounds(points: List<LatLon>): Bounds? {
        if (points.isEmpty()) return null
        var minLat = points[0].lat
        var maxLat = points[0].lat
        var minLon = points[0].lon
        var maxLon = points[0].lon
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        return Bounds(minLat, minLon, maxLat, maxLon)
    }
}

data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    val center: LatLon get() = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)

    /** Grows the box by [meters] on every side. */
    fun expanded(meters: Double): Bounds {
        val dLat = meters / 110_540.0
        val dLon = meters / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(1e-6))
        return Bounds(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    fun contains(point: LatLon): Boolean =
        point.lat in minLat..maxLat && point.lon in minLon..maxLon
}
