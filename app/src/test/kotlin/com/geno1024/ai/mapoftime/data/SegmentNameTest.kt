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
    ) = Edge(
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
            "A - B",
            segment("Main Street", userName = "My short cut", fromName = "A", toName = "B").displayName,
        )
    }

    @Test
    fun aCrossingPointNameCanFormAnEdgeName() {
        // A crossing derived from roads is a valid point display name. The edge
        // separator is "-", so the result stays unambiguous.
        val edge = segment(
            null,
            fromName = "万科翡丽山5号门",
            toName = "科创路 × 学府路",
        )
        assertEquals("万科翡丽山5号门 - 科创路 × 学府路", edge.pointName)
        assertEquals("万科翡丽山5号门 - 科创路 × 学府路", edge.displayName)
        assertEquals("万科翡丽山5号门 → 科创路 × 学府路", edge.forwardName)
    }

    @Test
    fun twoNamedEndsNameTheEdge() {
        assertEquals("north gate - metro", segment(null, fromName = "north gate", toName = "metro").displayName)
    }

    @Test
    fun thePointPairOutranksTheOsmName() {
        assertEquals(
            "north gate - metro",
            segment("Main Street", fromName = "north gate", toName = "metro").displayName,
        )
    }

    @Test
    fun oneNamedEndShowsNamedEndAndRawOtherEnd() {
        // A single named end is still useful: show it, keep the other end as its key.
        assertNull(segment("Main Street", fromName = "north gate").pointName)
        assertEquals("north gate - geo:2", segment("Main Street", fromName = "north gate").displayName)
        assertEquals("geo:1 - metro", segment("Main Street", toName = "metro").displayName)
    }

    @Test
    fun twoEndsWithTheSameJunctionNameAreNotAPair() {
        // Both ends of a link inside one junction carry the junction's name.
        // "X - X" describes nothing, so the road's own name takes over.
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
    fun aCompletelyUnnamedSegmentFallsBackToItsFormattedKey() {
        // The key is stable and never blank; format it for readability.
        assertEquals("geo:1 - geo:2", segment(name = null).displayName)
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
