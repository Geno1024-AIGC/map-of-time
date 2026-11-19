package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasemapTest {
    @Test
    fun everySourceFillsTheTilePlaceholders() {
        val osm = Basemap.OSM.tileUrl("", z = 15, x = 26978, y = 12417)
        assertEquals("https://tile.openstreetmap.org/15/26978/12417.png", osm)

        val amap = Basemap.AMAP.tileUrl("", z = 15, x = 26978, y = 12417)
        assertTrue(amap.startsWith("https://webrd0"))
        assertTrue(amap.contains("x=26978"))
        assertTrue(amap.contains("y=12417"))
        assertTrue(amap.contains("z=15"))
        assertFalse(amap.contains("{s}"))
    }

    @Test
    fun theSubdomainStaysWithinAutonavisFourHosts() {
        val seen = (0 until 8).map { x ->
            Basemap.AMAP.tileUrl("", z = 15, x = x, y = 0).substringAfter("webrd0").take(1)
        }.toSet()
        assertEquals(setOf("1", "2", "3", "4"), seen)
    }

    @Test
    fun theCustomSourceUsesTheUsersTemplate() {
        val url = Basemap.CUSTOM.tileUrl(
            "https://example.test/{s}/{z}/{x}/{y}.png",
            z = 3,
            x = 4,
            y = 5,
        )
        assertEquals("https://example.test/2/3/4/5.png", url)
    }

    @Test
    fun anEmptyCustomTemplateHasNoUrl() {
        assertEquals("", Basemap.CUSTOM.tileUrl("   ", z = 1, x = 0, y = 0))
    }

    @Test
    fun onlyAutonaviNeedsTheDatumShift() {
        assertTrue(Basemap.AMAP.gcj02)
        assertFalse(Basemap.OSM.gcj02)
        assertFalse(Basemap.CUSTOM.gcj02)
    }

    @Test
    fun anUnknownStoredIdFallsBackToOsm() {
        assertEquals(Basemap.OSM, Basemap.of(null))
        assertEquals(Basemap.OSM, Basemap.of("bing"))
        assertEquals(Basemap.AMAP, Basemap.of("amap"))
    }
}
