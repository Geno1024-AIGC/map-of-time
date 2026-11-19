package com.geno1024.ai.mapoftime.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The WGS-84 → GCJ-02 shift.
 *
 * Chinese law requires published maps to be obfuscated, so AutoNavi tiles are
 * drawn from GCJ-02 coordinates. Drawing a raw GPS fix on them lands the track
 * a few hundred metres away from the road it was recorded on. The shift is a
 * deterministic function of the coordinate, so it can be undone exactly where
 * it is needed without touching the stored data.
 */
object Gcj {
    private const val SEMI_MAJOR_AXIS = 6_378_245.0
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323

    /** Returns the GCJ-02 position of [at]; leaves points outside China alone. */
    fun toGcj02(at: LatLon): LatLon {
        if (outOfChina(at)) return at
        val dLat = transformLat(at.lon - 105.0, at.lat - 35.0)
        val dLon = transformLon(at.lon - 105.0, at.lat - 35.0)
        val radLat = at.lat / 180.0 * PI
        val magic = 1 - ECCENTRICITY_SQUARED * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        val lat = at.lat + (dLat * 180.0) /
            ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic) * PI)
        val lon = at.lon + (dLon * 180.0) /
            (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * PI)
        return LatLon(lat, lon)
    }

    private fun outOfChina(at: LatLon): Boolean =
        at.lon < 72.004 || at.lon > 137.8347 || at.lat < 0.8293 || at.lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLon(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }
}
