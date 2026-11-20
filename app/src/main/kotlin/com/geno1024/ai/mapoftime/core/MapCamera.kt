package com.geno1024.ai.mapoftime.core

import kotlin.math.pow

/**
 * The arithmetic behind dragging a slippy map around.
 *
 * At any continuous zoom the screen pixel and the world pixel are 1:1, because
 * the whole world is [TILE_PX] * 2^zoom pixels wide. Keeping that in one place
 * makes both gestures easy to reason about — and testable without Compose.
 */
object MapCamera {
    const val TILE_PX = 256.0

    fun worldSize(zoom: Double): Double = TILE_PX * 2.0.pow(zoom)

    fun worldX(lon: Double, world: Double): Double = (lon + 180.0) / 360.0 * world

    fun worldY(lat: Double, world: Double): Double = Geo.mercatorY(lat) * world

    fun lonAt(x: Double, world: Double): Double = x / world * 360.0 - 180.0

    fun latAt(y: Double, world: Double): Double = Geo.mercatorLat(y / world)

    /**
     * Metres covered by one world pixel at [lat]. Used to draw something that
     * has a real size — a junction's radius — at the right scale, which is
     * what keeps a dashed circle meaningful when the user pinches the map.
     */
    fun metersPerPixel(lat: Double, world: Double): Double =
        EARTH_CIRCUMFERENCE_M * kotlin.math.cos(Math.toRadians(lat)) / world

    /**
     * Drags the map by [dx] / [dy] screen pixels.
     *
     * A drag must travel exactly as far as the finger. Anything that adds an
     * offset — for instance treating the finger position as a zoom focus —
     * turns a 5 px nudge into a jump of half the viewport.
     */
    fun pan(center: LatLon, zoom: Double, dx: Double, dy: Double): LatLon {
        val world = worldSize(zoom)
        return LatLon(
            latAt(worldY(center.lat, world) - dy, world).coerceIn(-85.0, 85.0),
            lonAt(worldX(center.lon, world) - dx, world),
        )
    }

    /**
     * Zooms by [ratio] while keeping the world point under
     * ([focusX], [focusY]) exactly where it is.
     *
     * The focus offset from the centre has to be scaled along with everything
     * else; anchoring only the centre makes pinching near an edge throw the
     * map sideways.
     */
    fun zoomAbout(
        center: LatLon,
        zoom: Double,
        ratio: Double,
        focusX: Double,
        focusY: Double,
        width: Double,
        height: Double,
    ): LatLon {
        val world = worldSize(zoom)
        val newWorld = world * ratio
        val centerX = worldX(center.lon, world)
        val centerY = worldY(center.lat, world)
        val anchorX = (centerX + focusX - width / 2.0) * ratio - focusX + width / 2.0
        val anchorY = (centerY + focusY - height / 2.0) * ratio - focusY + height / 2.0
        return LatLon(
            latAt(anchorY, newWorld).coerceIn(-85.0, 85.0),
            lonAt(anchorX, newWorld),
        )
    }
}

/** Equatorial circumference: the width of the world at zoom 0, in metres. */
private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
