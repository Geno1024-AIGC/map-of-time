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

    private fun segment(from: PointRef, to: PointRef): Segment {
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
            lengthM = 30.0,
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
    private fun wideCrossing(): Pair<List<PointRef>, List<Segment>> {
        val columns = listOf(-45.0, -15.0, 15.0, 45.0)
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
}
