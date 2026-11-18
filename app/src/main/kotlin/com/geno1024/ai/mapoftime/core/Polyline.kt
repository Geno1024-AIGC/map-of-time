package com.geno1024.ai.mapoftime.core

/**
 * Google-polyline compatible encoding at 1e-5 precision. Leg geometry lives in
 * SQLite, and a few hundred coordinates cost more as JSON text than as this.
 */
object Polyline {
    fun encode(points: List<LatLon>): String {
        val out = StringBuilder(points.size * 6)
        var lastLat = 0
        var lastLon = 0
        for (p in points) {
            val lat = Math.round(p.lat * 1e5).toInt()
            val lon = Math.round(p.lon * 1e5).toInt()
            encodeValue(lat - lastLat, out)
            encodeValue(lon - lastLon, out)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    fun decode(value: String): List<LatLon> {
        if (value.isEmpty()) return emptyList()
        val points = ArrayList<LatLon>(value.length / 5)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < value.length) {
            lat += decodeValue(value, index).also { index = it.second }.first
            val lonPair = decodeValue(value, index)
            lon += lonPair.first
            index = lonPair.second
            points += LatLon(lat / 1e5, lon / 1e5)
        }
        return points
    }

    private fun encodeValue(value: Int, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            out.append((((v and 0x1f) or 0x20) + 63).toChar())
            v = v ushr 5
        }
        out.append((v + 63).toChar())
    }

    private fun decodeValue(value: String, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var index = start
        while (true) {
            val b = value[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
            if (index >= value.length) break
        }
        val v = if ((result and 1) == 1) (result shr 1).inv() else result shr 1
        return v to index
    }
}
