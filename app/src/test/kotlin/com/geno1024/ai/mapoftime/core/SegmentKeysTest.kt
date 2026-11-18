package com.geno1024.ai.mapoftime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentKeysTest {
    @Test
    fun endpointOrderDoesNotCreateASecondIdentity() {
        assertEquals(SegmentKeys.osm(4, 9), SegmentKeys.osm(9, 4))
        assertEquals("osm:4-9", SegmentKeys.osm(9, 4))
        assertEquals("geo:7-8", SegmentKeys.geo(8, 7))
    }

    @Test
    fun prefixesKeepTheThreeKindsApart() {
        assertEquals("mix:1-2", SegmentKeys.mixed(2, 1))
        // Same ids, three different pieces of road.
        assertEquals(3, setOf(SegmentKeys.osm(1, 2), SegmentKeys.geo(1, 2), SegmentKeys.mixed(1, 2)).size)
    }

    @Test
    fun directionIsPositiveWhenIdsIncrease() {
        assertEquals(1, SegmentKeys.direction(1, 5))
        assertEquals(-1, SegmentKeys.direction(5, 1))
        assertEquals(1, SegmentKeys.direction(3, 3))
    }
}
