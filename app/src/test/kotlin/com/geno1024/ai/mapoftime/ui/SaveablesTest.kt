package com.geno1024.ai.mapoftime.ui

import androidx.compose.runtime.saveable.SaverScope
import com.geno1024.ai.mapoftime.core.TripMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen state savers are used to keep filters and selection when a list
 * screen is disposed by navigation, so a broken round-trip would silently
 * reset what the user was looking at. They are plain objects, testable without
 * a Compose host.
 */
class SaveablesTest {
    private val scope = SaverScope { true }

    @Test
    fun nonNullEnumRoundTripsByName() {
        val saver = enumNameSaver(TripMode.entries.toTypedArray())
        for (mode in TripMode.entries) {
            val saved = checkNotNull(with(saver) { scope.save(mode) })
            assertEquals(mode.name, saved)
            assertEquals(mode, saver.restore(saved))
        }
    }

    @Test
    fun nullableEnumRoundTripsIncludingNull() {
        val saver = nullableEnumNameSaver(TripMode.entries.toTypedArray())
        assertEquals("", with(saver) { scope.save(null) })
        assertNull(saver.restore(""))
        for (mode in TripMode.entries) {
            val saved = checkNotNull(with(saver) { scope.save(mode) })
            assertEquals(mode, saver.restore(saved))
        }
    }

    @Test
    fun anUnrecognisedEnumNameRestoresToNull() {
        val saver = nullableEnumNameSaver(TripMode.entries.toTypedArray())
        assertNull(saver.restore("a mode from a newer build"))
    }

    @Test
    fun longSetRoundTrips() {
        val values = setOf(7L, 12L, 3L)
        val saved = checkNotNull(with(longSetSaver) { scope.save(values) })
        assertEquals(values.toList().toSet(), saved.toSet())
        assertEquals(values, longSetSaver.restore(saved))
    }

    @Test
    fun emptyLongSetRoundTrips() {
        val saved = checkNotNull(with(longSetSaver) { scope.save(emptySet()) })
        assertTrue(saved.isEmpty())
        assertEquals(emptySet<Long>(), longSetSaver.restore(saved))
    }
}
