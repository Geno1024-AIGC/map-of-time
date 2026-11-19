package com.geno1024.ai.mapoftime.data

import com.geno1024.ai.mapoftime.core.TripMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentNameTest {
    private fun segment(name: String?, userName: String? = null) = Segment(
        id = 1,
        key = "geo:1-2",
        mode = TripMode.WALK,
        name = name,
        identity = MatchKind.GEOMETRIC,
        aLat = 0.0,
        aLon = 0.0,
        bLat = 0.0,
        bLon = 0.0,
        lengthM = 100.0,
        firstSeen = 0,
        lastSeen = 0,
        userName = userName,
    )

    @Test
    fun aNameTheUserTypedWinsOverTheOsmName() {
        assertEquals("My short cut", segment("Main Street", "My short cut").displayName)
    }

    @Test
    fun withoutAUserNameTheOsmNameIsUsed() {
        assertEquals("Main Street", segment("Main Street").displayName)
    }

    @Test
    fun aCompletelyUnnamedSegmentFallsBackToItsKey() {
        // The key is ugly but stable, and never blank: the list needs something
        // to show and something to search.
        assertEquals("geo:1-2", segment(name = null).displayName)
    }
}
