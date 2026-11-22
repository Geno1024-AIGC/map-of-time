package com.geno1024.ai.mapoftime.data

import com.geno1024.ai.mapoftime.core.TripMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentNameTest {
    private fun segment(
        name: String?,
        userName: String? = null,
        fromName: String? = null,
        toName: String? = null,
    ) = Segment(
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
        fromKey = "geo:1",
        toKey = "geo:2",
        fromName = fromName,
        toName = toName,
    )

    @Test
    fun aRoadNameOnTheSegmentDoesNotOverrideThePointPair() {
        // A road name typed on the segment is inference input for the points it
        // touches. The segment itself is always named by its two atomic ends.
        assertEquals(
            "A × B",
            segment("Main Street", userName = "My short cut", fromName = "A", toName = "B").displayName,
        )
    }

    @Test
    fun aCompositeRoadNameCannotLeakIntoASegmentName() {
        // "科创路 × 学府路" is a crossing of roads, not a point name. Joining it
        // with the other end used to spell "gate × road × road" — three parts.
        val leaked = segment(
            null,
            fromName = "万科翡丽山5号门",
            toName = "科创路 × 学府路",
        )
        assertNull(leaked.pointName)
        assertNull(leaked.forwardName)
        assertEquals("geo:1-2", leaked.displayName)
    }

    @Test
    fun twoNamedEndsNameTheSegment() {
        assertEquals("north gate × metro", segment(null, fromName = "north gate", toName = "metro").displayName)
    }

    @Test
    fun thePointPairOutranksTheOsmName() {
        assertEquals(
            "north gate × metro",
            segment("Main Street", fromName = "north gate", toName = "metro").displayName,
        )
    }

    @Test
    fun oneNamedEndIsNotEnough() {
        // "north gate × ..." says less than the road's own name, so OSM wins.
        assertNull(segment("Main Street", fromName = "north gate").pointName)
        assertEquals("Main Street", segment("Main Street", fromName = "north gate").displayName)
    }

    @Test
    fun twoEndsWithTheSameJunctionNameAreNotAPair() {
        // Both ends of a link inside one junction carry the junction's name.
        // "X × X" describes nothing, so the road's own name takes over.
        val inside = segment("Jianguo Road", fromName = "Renmin Gate", toName = "Renmin Gate")
        assertNull(inside.pointName)
        assertNull(inside.forwardName)
        assertEquals("Jianguo Road", inside.displayName)
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

    @Test
    fun directionsReadInTheOrderTheyAreTravelled() {
        val named = segment(null, fromName = "north gate", toName = "metro")
        assertEquals("north gate → metro", named.forwardName)
        assertEquals("metro → north gate", named.backwardName)
        assertNull(segment(name = null).forwardName)
    }

    @Test
    fun aTraversalStartsAtTheEndItActuallyStartedFrom() {
        val named = segment(null, fromName = "north gate", toName = "metro")
        assertEquals(
            "north gate" to "metro",
            traversalEndNames(named, direction = 1, fromFallback = "From", toFallback = "To"),
        )
        // Coming back, the trip starts at the segment's to end.
        assertEquals(
            "metro" to "north gate",
            traversalEndNames(named, direction = -1, fromFallback = "From", toFallback = "To"),
        )
        // Unnamed ends still say which end of the segment they are.
        assertEquals(
            "To" to "From",
            traversalEndNames(segment(null), direction = -1, fromFallback = "From", toFallback = "To"),
        )
    }

    @Test
    fun pointKeysParseOnlyTheKindsWeCanName() {
        assertEquals(PointRef("osm", 123), PointRef.parse("osm:123"))
        assertEquals(PointRef("geo", 7), PointRef.parse("geo:7"))
        assertNull(PointRef.parse("mix:7-9"))
        assertNull(PointRef.parse("osm:"))
        assertNull(PointRef.parse(null))
    }
}
