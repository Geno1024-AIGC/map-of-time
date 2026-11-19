package com.geno1024.ai.mapoftime.core

import com.geno1024.ai.mapoftime.core.Geo.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcjTest {
    private fun assertShifted(expected: LatLon, actual: LatLon) {
        assertEquals(expected.lat, actual.lat, 1e-6)
        assertEquals(expected.lon, actual.lon, 1e-6)
    }

    @Test
    fun beijingShiftsRoughlyHalfAKilometre() {
        val wgs = LatLon(39.908692, 116.397477)
        assertShifted(LatLon(39.91009549393981, 116.40372056710368), Gcj.toGcj02(wgs))
        // Sanity: a shift large enough to matter at street level, small enough
        // to stay in the same neighbourhood.
        val metres = distanceMeters(wgs, Gcj.toGcj02(wgs))
        assertTrue("shifted $metres m", metres in 400.0..700.0)
    }

    @Test
    fun shanghaiShiftsToo() {
        assertShifted(
            LatLon(31.22845773757727, 121.47822305927693),
            Gcj.toGcj02(LatLon(31.2304, 121.4737)),
        )
    }

    @Test
    fun pointsOutsideChinaAreUntouched() {
        val paris = LatLon(48.8566, 2.3522)
        val tokyo = LatLon(35.6762, 139.6503)
        assertEquals(paris, Gcj.toGcj02(paris))
        assertEquals(tokyo, Gcj.toGcj02(tokyo))
    }

    @Test
    fun theShiftIsStableAndContinuous() {
        val a = Gcj.toGcj02(LatLon(39.9000, 116.3000))
        val b = Gcj.toGcj02(LatLon(39.9000, 116.3001))
        assertTrue(distanceMeters(a, b) in 8.0..9.0)
    }
}
