package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Place
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceDraftTest {
    /** One degree of latitude is ~111 km, so one metre is a readable fraction. */
    private val metre = 1.0 / 111_320.0
    private val origin = LatLon(31.2, 121.4)

    private var nextId = 1L
    private var nextNode = 1L
    private val coords = HashMap<String, LatLon>()

    /** Registers a point [eastM] / [northM] from the origin and returns its ref. */
    private fun point(eastM: Double, northM: Double): PointRef {
        val ref = PointRef("osm", nextNode++)
        // Longitude shrinks with latitude; at 31.2° one metre is ~0.855 of a
        // metre of latitude, which is close enough for a grouping test.
        coords[ref.key] = LatLon(origin.lat + northM * metre, origin.lon + eastM * metre / 0.855)
        return ref
    }

    private fun segment(from: PointRef, to: PointRef, lengthM: Double = 30.0): Segment {
        val a = coords.getValue(from.key)
        val b = coords.getValue(to.key)
        return Segment(
            id = nextId++,
            key = "${from.key}-${to.key}",
            mode = TripMode.DRIVE,
            name = null,
            identity = MatchKind.OSM,
            aLat = a.lat,
            aLon = a.lon,
            bLat = b.lat,
            bLon = b.lon,
            lengthM = lengthM,
            firstSeen = 0,
            lastSeen = 0,
            fromKey = from.key,
            toKey = to.key,
        )
    }

    /**
     * A crossing about 90 m across: three entry/exit pairs per road and a
     * refuge at each corner. That is the shape the user sees in OSM, and the
     * reason naming one point at a time never finishes.
     */
    private fun wideCrossing(offsetEastM: Double = 0.0): Pair<List<PointRef>, List<Segment>> {
        val columns = listOf(-45.0, -15.0, 15.0, 45.0).map { it + offsetEastM }
        val rows = listOf(-30.0, 0.0, 30.0)
        val grid = rows.map { north -> columns.map { east -> point(east, north) } }
        val segments = ArrayList<Segment>()
        for (r in rows.indices) {
            for (c in columns.indices) {
                if (c + 1 < columns.size) segments += segment(grid[r][c], grid[r][c + 1])
                if (r + 1 < rows.size) segments += segment(grid[r][c], grid[r + 1][c])
            }
        }
        return grid.flatten() to segments
    }

    @Test
    fun aWideCrossingIsOneJunction() {
        val (points, segments) = wideCrossing()
        assertEquals(12, points.size)

        val draft = PlaceDrafts.draft(points.first(), segments)!!
        assertEquals(12, draft.count)
        // Corners 90 m apart still land in one proposal.
        assertTrue(draft.spanM > 85.0)
    }

    @Test
    fun theApproachRoadIsLeftOut() {
        val (points, segments) = wideCrossing()
        val seed = points.first()
        val farAway = point(300.0, 0.0)

        val draft = PlaceDrafts.draft(seed, segments + segment(seed, farAway))!!
        assertEquals(12, draft.count)
        assertTrue(draft.members.none { it.ref == farAway })
    }

    @Test
    fun pointsOfAnotherNamedJunctionAreNotSwallowed() {
        val seed = point(0.0, 0.0)
        val neighbour = point(30.0, 0.0)
        // The stranger is 60 m from the seed — outside it — but only 30 m from
        // the neighbour. Adopting it would silently rename another crossing.
        val stranger = point(60.0, 0.0)
        val other = Place(
            id = 7,
            name = "the next one",
            at = coords.getValue(stranger.key),
            radiusM = 20.0,
            points = listOf(stranger),
        )

        val draft = PlaceDrafts.draft(seed, listOf(segment(seed, neighbour)), listOf(other))!!
        assertEquals(2, draft.count)
        assertTrue(draft.members.none { it.ref == stranger })
    }

    @Test
    fun editingANamedJunctionKeepsThePointsThatHaveNoPieceBetweenThem() {
        val first = point(0.0, 0.0)
        val second = point(100.0, 0.0)
        // Two corners of the same crossing whose connecting piece has not been
        // driven: nothing joins them but the name itself.
        val place = Place(
            id = 3,
            name = "Renmin × Jianguo",
            at = coords.getValue(first.key),
            radiusM = 80.0,
            points = listOf(first, second),
        )
        val elsewhere = point(0.0, 100.0)
        val segments = listOf(segment(first, elsewhere))

        val draft = PlaceDrafts.draft(first, segments, listOf(place))!!
        assertEquals(3L, draft.existingPlaceId)
        assertTrue(draft.refs.any { it == second })
    }

    @Test
    fun aPointWithNoStoredPieceStillGetsADraft() {
        // The caller knows where the tapped end is even when no segment of the
        // history mentions it; without that fallback the tap would do nothing.
        val lonely = PointRef("osm", 77)
        val draft = PlaceDrafts.draft(lonely, emptyList(), emptyList(), origin)!!
        assertEquals(1, draft.count)
        assertEquals(lonely, draft.seed)
    }

    @Test
    fun anUnknownPointCannotBecomeAJunction() {
        assertNull(PlaceDrafts.draft(PointRef("osm", 42), emptyList()))
    }

    @Test
    fun aOnePointJunctionStillAdoptsItsNeighbourhood() {
        val one = point(0.0, 0.0)
        val two = point(30.0, 0.0)
        val draft = PlaceDrafts.draft(one, listOf(segment(one, two)))!!
        assertEquals(2, draft.count)
        assertEquals(PlaceDrafts.MIN_ADOPT_M, draft.adoptRadius(), 0.01)
    }

    @Test
    fun aBigInterchangeNeedsAWiderReach() {
        val (near, nearSegments) = wideCrossing()
        val (far, farSegments) = wideCrossing(offsetEastM = 200.0)
        val seed = near.first()
        // 55 m pieces are longer than the 45 m default but shorter than the
        // 90 m limit at 300 m. That is exactly the gap the Wider button fills.
        val middle = point(100.0, 0.0)
        val bridge = listOf(
            segment(near[7], middle, 55.0), // east end of the near crossing
            segment(middle, far[4], 55.0), // west end of the far one
        )
        val segments = nearSegments + farSegments + bridge

        val tight = PlaceDrafts.draft(seed, segments)!!
        assertEquals(12, tight.count)
        assertTrue(tight.members.none { it.ref in far })

        val wide = PlaceDrafts.draft(seed, segments, reachM = 300.0)!!
        assertTrue(wide.count > 20)
        assertTrue(far.all { ref -> wide.refs.any { it == ref } })
    }

    @Test
    fun aWiderReachStillLeavesTheRoadAfterTheJunctionOut() {
        val (points, segments) = wideCrossing()
        val seed = points.first()
        val downTheRoad = point(300.0, 0.0)
        val draft = PlaceDrafts.draft(
            seed,
            segments + segment(seed, downTheRoad, 300.0),
            reachM = 400.0,
        )!!
        assertTrue(draft.members.none { it.ref == downTheRoad })
    }

    @Test
    fun wideningStillRespectsAnotherNamedJunction() {
        val seed = point(0.0, 0.0)
        val middle = point(100.0, 0.0)
        val otherSeed = point(200.0, 0.0)
        val neighbour = point(230.0, 0.0)
        val other = Place(
            id = 7,
            name = "the next one",
            at = coords.getValue(otherSeed.key),
            radiusM = 80.0,
            points = listOf(otherSeed, neighbour),
        )
        val segments = listOf(
            segment(seed, middle, 100.0),
            segment(middle, otherSeed, 100.0),
        )
        val draft = PlaceDrafts.draft(seed, segments, listOf(other), reachM = 300.0)!!
        assertTrue(draft.members.none { it.ref == otherSeed || it.ref == neighbour })
    }

    @Test
    fun aPickedPointCanBeAddedBeyondTheReach() {
        val (points, segments) = wideCrossing()
        val seed = points.first()
        val far = point(400.0, 0.0)
        val draft = PlaceDrafts.draft(
            seed,
            segments + segment(seed, far, 400.0),
            manual = mapOf(far.key to true),
        )!!
        assertTrue(draft.refs.any { it == far })
        assertTrue(draft.spanM > 300.0)
    }

    @Test
    fun aPickedPointCanBeRemovedFromTheAutomaticDraft() {
        val (points, segments) = wideCrossing()
        val seed = points.first()
        val unwanted = points[7] // east end, inside the automatic 150 m draft
        val draft = PlaceDrafts.draft(seed, segments, manual = mapOf(unwanted.key to false))!!
        assertEquals(11, draft.count)
        assertTrue(draft.refs.none { it == unwanted })
    }

    @Test
    fun aPickedPointCannotStealAnotherPlacesPoint() {
        val seed = point(0.0, 0.0)
        val otherSeed = point(80.0, 0.0)
        val other = Place(
            id = 9,
            name = "the next one",
            at = coords.getValue(otherSeed.key),
            radiusM = 25.0,
            points = listOf(otherSeed),
        )
        val draft = PlaceDrafts.draft(
            seed,
            listOf(segment(seed, otherSeed)),
            listOf(other),
            manual = mapOf(otherSeed.key to true),
        )!!
        assertTrue(draft.refs.none { it == otherSeed })
    }

    @Test
    fun theSeedCannotBePickedAway() {
        val (points, segments) = wideCrossing()
        val seed = points.first()
        val draft = PlaceDrafts.draft(seed, segments, manual = mapOf(seed.key to false))!!
        assertEquals(12, draft.count)
        assertTrue(draft.refs.any { it == seed })
    }

    @Test
    fun twoHalvesOfOneWideJunctionFoldIntoOneDraft() {
        // The through movement and the right-turn-only lane of one big T were
        // named separately because 70 m is too far for the default near rule.
        // They share a name — that, not geometry, says they are one junction.
        val through = point(0.0, 0.0)
        val rightTurn = point(70.0, 0.0)
        val name = "Guanzhang x Shuilian"
        val a = Place(id = 1, name = name, at = coords.getValue(through.key), radiusM = 25.0, points = listOf(through))
        val b = Place(id = 2, name = name, at = coords.getValue(rightTurn.key), radiusM = 25.0, points = listOf(rightTurn))
        // No short piece between them — only a long approach road, as in geo: data.
        val segments = listOf(segment(through, point(400.0, 0.0), 4000.0))

        val draft = PlaceDrafts.draft(through, segments, listOf(a, b))!!
        assertEquals(2, draft.count)
        assertTrue(rightTurn in draft.refs)
        assertEquals(1L, draft.existingPlaceId)
        assertEquals(listOf(2L), draft.absorbIds)
    }

    @Test
    fun sameNameRowsFarBeyondReachAreNotFolded() {
        val through = point(0.0, 0.0)
        val otherTown = point(400.0, 0.0)
        val name = "Renmin x Jianguo"
        val a = Place(id = 1, name = name, at = coords.getValue(through.key), radiusM = 25.0, points = listOf(through))
        val b = Place(id = 2, name = name, at = coords.getValue(otherTown.key), radiusM = 25.0, points = listOf(otherTown))

        val draft = PlaceDrafts.draft(
            through, emptyList(), listOf(a, b), coords.getValue(through.key),
        )!!
        assertEquals(1, draft.count)
        assertTrue(draft.absorbIds.isEmpty())
    }

    @Test
    fun aNamedPointBesideTheJunctionIsNotAutoAbsorbed() {
        val seed = point(0.0, 0.0)
        val station = point(70.0, 0.0)
        // At 300 m the near limit is 100 m, so geometry alone would take it.
        val draft = PlaceDrafts.draft(
            seed,
            listOf(segment(seed, station, 5000.0)),
            namedKeys = setOf(station.key),
            reachM = 300.0,
        )!!
        assertEquals(1, draft.count)
        assertTrue(station !in draft.refs)
    }

    @Test
    fun theShortRoadToANamedPointIsAlsoKeptOut() {
        val seed = point(0.0, 0.0)
        val entrance = point(70.0, 0.0) // named filling-station entrance
        val exit = point(90.0, 0.0) // unnamed exit, joined by a short piece
        val draft = PlaceDrafts.draft(
            seed,
            listOf(segment(seed, entrance, 5000.0), segment(entrance, exit, 20.0)),
            namedKeys = setOf(entrance.key),
            reachM = 300.0,
        )!!
        assertEquals(1, draft.count)
        assertTrue(entrance !in draft.refs)
        assertTrue(exit !in draft.refs)
    }

    @Test
    fun aNamedPointCanStillBePickedOnPurpose() {
        val seed = point(0.0, 0.0)
        val station = point(70.0, 0.0)
        val draft = PlaceDrafts.draft(
            seed,
            listOf(segment(seed, station, 5000.0)),
            namedKeys = setOf(station.key),
            reachM = 300.0,
            manual = mapOf(station.key to true),
        )!!
        assertTrue(station in draft.refs)
    }

    @Test
    fun theWideJunctionMergesWithoutTakingTheFillingStation() {
        val through = point(0.0, 0.0)
        val rightTurn = point(70.0, 0.0)
        val stationIn = point(-10.0, -70.0)
        val stationOut = point(15.0, -70.0)
        val name = "Guanzhang x Shuilian"
        val a = Place(id = 1, name = name, at = coords.getValue(through.key), radiusM = 25.0, points = listOf(through))
        val b = Place(id = 2, name = name, at = coords.getValue(rightTurn.key), radiusM = 25.0, points = listOf(rightTurn))
        val segments = listOf(
            segment(stationIn, stationOut, 26.0), // entry ↔ exit, a short piece
            segment(through, point(500.0, 0.0), 5000.0),
        )

        val draft = PlaceDrafts.draft(
            through,
            segments,
            listOf(a, b),
            reachM = 300.0,
            namedKeys = setOf(stationIn.key),
        )!!
        assertEquals(2, draft.count)
        assertTrue(rightTurn in draft.refs)
        assertTrue(stationIn !in draft.refs)
        assertTrue(stationOut !in draft.refs)
        assertEquals(listOf(2L), draft.absorbIds)
    }

    @Test
    fun aWideCircleDoesNotAdoptTheNextCrossingDownTheRoad() {
        val first = point(0.0, 0.0)
        val member = point(30.0, 0.0)
        val place = Place(
            id = 1,
            name = "a big interchange",
            at = coords.getValue(first.key),
            radiusM = 160.0,
            points = listOf(first, member),
        )
        val inside = point(20.0, 20.0)
        val nextCrossing = point(150.0, 0.0)
        val outsideRadius = point(200.0, 0.0)
        assertTrue(PlaceDrafts.adopts(place, coords.getValue(inside.key)) { coords[it.key] })
        assertTrue(!PlaceDrafts.adopts(place, coords.getValue(nextCrossing.key)) { coords[it.key] })
        assertTrue(!PlaceDrafts.adopts(place, coords.getValue(outsideRadius.key)) { coords[it.key] })
    }
}
