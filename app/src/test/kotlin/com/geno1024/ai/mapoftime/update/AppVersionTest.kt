package com.geno1024.ai.mapoftime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesTheStampOutOfAnAssetName() {
        val version = AppVersion.parse("map-of-time-0.1.37.7.82fdc33a-build13-82fdc33a.apk")
        assertEquals(AppVersion(0, 1, 37, 7, "82fdc33a"), version)
    }

    @Test
    fun parsesAnInstalledVersionName() {
        assertEquals(AppVersion(0, 1, 37, 7, "82fdc33a"), AppVersion.parse("0.1.37.7.82fdc33a"))
        assertEquals(AppVersion(0, 1, 37, 7, "82fdc33a"), AppVersion.parse("0.1.37.7.82fdc33a-debug"))
    }

    @Test
    fun envAloneOrdersBuilds() {
        val installed = AppVersion.parse("0.1.37.7.82fdc33a")!!
        val newer = AppVersion.parse("0.1.38.3.aaaaaaaa")!!
        val sameCommit = AppVersion.parse("0.1.37.99.82fdc33a")!!

        assertTrue(newer > installed)
        // A larger pack is just one more packaging attempt of the same code.
        assertEquals(0, sameCommit.compareTo(installed))
        // Nor does raising major.minor make the same commit newer.
        val raisedBase = AppVersion.parse("0.2.37.7.82fdc33a")!!
        assertEquals(0, raisedBase.compareTo(installed))
        assertFalse(raisedBase == installed)
    }

    @Test
    fun rejectsThingsThatAreNotStamps() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("canary"))
        assertNull(AppVersion.parse("0.1.37.82fdc33a"))
        assertNull(AppVersion.parse("0.1.37.7.ZZZZZZZZ"))
    }
}
