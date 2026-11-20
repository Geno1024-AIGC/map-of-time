package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MapCameraTest {
    private val center = LatLon(39.9042, 116.4074)
    private val zoom = 16.0

    /** Where [point] lands on screen given a camera [center] at [zoom]. */
    private fun screenX(point: LatLon, center: LatLon, zoom: Double, width: Double): Double {
        val world = MapCamera.worldSize(zoom)
        return MapCamera.worldX(point.lon, world) - MapCamera.worldX(center.lon, world) + width / 2.0
    }

    private fun screenY(point: LatLon, center: LatLon, zoom: Double, height: Double): Double {
        val world = MapCamera.worldSize(zoom)
        return MapCamera.worldY(point.lat, world) - MapCamera.worldY(center.lat, world) + height / 2.0
    }

    @Test
    fun aDragMovesTheMapExactlyAsFarAsTheFinger() {
        val moved = MapCamera.pan(center, zoom, dx = 5.0, dy = 0.0)

        // A 5 px nudge must shift the view by 5 px, not by the half-screen the
        // focus-anchored formula used to add on every gesture event.
        assertEquals(5.0, screenX(center, moved, zoom, width = 1000.0) - 500.0, 1e-6)
        assertEquals(center.lat, moved.lat, 1e-9)
    }

    @Test
    fun aVerticalDragDoesNotMoveSideways() {
        val moved = MapCamera.pan(center, zoom, dx = 0.0, dy = 5.0)

        assertEquals(center.lon, moved.lon, 1e-9)
        assertEquals(5.0, screenY(center, moved, zoom, height = 600.0) - 300.0, 1e-6)
    }

    @Test
    fun zoomingAboutTheCentreKeepsTheCentre() {
        val zoomed = MapCamera.zoomAbout(
            center = center,
            zoom = zoom,
            ratio = 2.0,
            focusX = 500.0,
            focusY = 300.0,
            width = 1000.0,
            height = 600.0,
        )

        assertEquals(center.lat, zoomed.lat, 1e-9)
        assertEquals(center.lon, zoomed.lon, 1e-9)
    }

    @Test
    fun zoomingAboutAnOffCentrePointKeepsThatPointUnderTheFinger() {
        val width = 1000.0
        val height = 600.0
        val focusX = 120.0
        val focusY = 80.0
        val world = MapCamera.worldSize(zoom)

        // The geographic point currently under the finger.
        val point = LatLon(
            MapCamera.latAt(MapCamera.worldY(center.lat, world) + focusY - height / 2.0, world),
            MapCamera.lonAt(MapCamera.worldX(center.lon, world) + focusX - width / 2.0, world),
        )

        val zoomed = MapCamera.zoomAbout(
            center = center,
            zoom = zoom,
            ratio = 2.0,
            focusX = focusX,
            focusY = focusY,
            width = width,
            height = height,
        )

        assertEquals(focusX, screenX(point, zoomed, zoom + 1.0, width), 1e-6)
        assertEquals(focusY, screenY(point, zoomed, zoom + 1.0, height), 1e-6)
    }

    @Test
    fun aWorldPixelIsAHundredAndFiftyKilometresAtTheEquator() {
        // One tile of the world at zoom 0: 156 543 m * 256 px ≈ 40 075 km.
        val metres = MapCamera.metersPerPixel(0.0, MapCamera.TILE_PX)
        assertEquals(156_543.0, metres, 1.0)
    }

    @Test
    fun aJunctionStaysTheSameSizeOnTheGroundWhenZooming() {
        // The ring is drawn in pixels but means metres: at twice the zoom the
        // same radius must take twice the pixels.
        val world = MapCamera.worldSize(18.0)
        val close = MapCamera.metersPerPixel(31.2, world)
        val further = MapCamera.metersPerPixel(31.2, world * 2.0)
        assertEquals(close / 2.0, further, 1e-9)
    }
}
