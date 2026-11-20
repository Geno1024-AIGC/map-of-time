package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.data.Place
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.Segment

/** One point that a proposed junction would cover. */
data class PlaceMember(val ref: PointRef, val at: LatLon, val distanceM: Double)

/**
 * The junction the app thinks the user is pointing at: the tapped point plus
 * everything that belongs with it, ready to be shown as one name.
 *
 * It is only a proposal. The user sees how many points it covers and how wide
 * it is, and can drop back to naming a single point; the rules below exist to
 * make the common case right, not to decide for them.
 */
data class PlaceDraft(
    val seed: PointRef,
    val center: LatLon,
    val members: List<PlaceMember>,
    /** Distance between the two furthest members, for the "how wide" line. */
    val spanM: Double,
    /** Furthest member from [center]; a new point inside it is adopted. */
    val radiusM: Double,
    val existingPlaceId: Long? = null,
    /**
     * Members of an existing junction that no stored segment mentions any
     * more. They cannot be placed on the map, but dropping them here would
     * quietly release points the user already named.
     */
    val carried: List<PointRef> = emptyList(),
) {
    val count: Int get() = members.size + carried.size

    val refs: List<PointRef> get() = members.map { it.ref } + carried

    fun adoptRadius(): Double = radiusM.coerceAtLeast(PlaceDrafts.MIN_ADOPT_M)
}

/**
 * Groups the points the user has actually travelled through into junctions.
 *
 * Two points belong together when the road between them is short, or when they
 * lie close to each other: an intersection is a knot of 20–40 m pieces, while
 * the approach roads that leave it are hundreds of metres long. The walk from
 * the seed is capped so a chain of nearby corners cannot swallow a whole
 * street, and points that already belong to another named junction are left
 * alone — that is the one mistake the user could not undo by renaming.
 *
 * ## Why there is a reach the user can raise
 *
 * A motorway interchange really can be 300 m across, and in the driven graph it
 * looks exactly like 300 m of street: a chain of points joined by pieces of
 * 80–130 m. No threshold can tell those apart, so the app does not pretend to
 * guess. The default stays tight — it is right for the crossings you meet every
 * day — and the preview offers the next reach up, with the circle redrawn so
 * the cost of the decision is visible before anything is saved. Raising it
 * scales every threshold together (the same rules, zoomed), which is what keeps
 * the wide case from needing its own set of constants.
 */
object PlaceDrafts {
    /** A piece of road this short is inside a junction, not between two. */
    const val SHORT_EDGE_M = 45.0

    /** Two points this close are the same knot even without a piece between them. */
    const val NEAR_M = 50.0

    /** No proposal reaches further than this from the point that was tapped. */
    const val SPAN_M = 150.0

    /**
     * The reaches the preview can step through. 150 m is the default; 300 m is
     * a big interchange; 400 m is the ceiling, and it is deliberately not
     * "unlimited" — past that the circle stops describing one junction.
     */
    val REACH_STEPS_M = listOf(SPAN_M, 200.0, 300.0, 400.0)

    /** A gate against a pathological chain; a real junction is well under this. */
    const val MAX_POINTS = 32

    /** Adoption needs a floor, or a one-point junction would swallow nothing. */
    const val MIN_ADOPT_M = 25.0

    fun draft(
        seed: PointRef,
        segments: List<Segment>,
        places: List<Place> = emptyList(),
        /** Where the seed is, when no stored segment mentions it. */
        fallbackAt: LatLon? = null,
        /**
         * How far the proposal may reach. Usually one of [REACH_STEPS_M]; the
         * edge and proximity thresholds grow with it in proportion, so the
         * default 150 m gives exactly the 45 m / 50 m rules above.
         */
        reachM: Double = SPAN_M,
        /**
         * Points the user picked in the preview, after seeing the circle:
         * `true` = pull this one in, `false` = leave it out. The seed is always
         * kept — removing it would dissolve the junction rather than edit it.
         * Picks override the automatic rules, but still cannot take a point
         * that belongs to another named junction.
         */
        manual: Map<String, Boolean> = emptyMap(),
    ): PlaceDraft? {
        val scale = reachM / SPAN_M
        val edgeLimit = SHORT_EDGE_M * scale
        val nearLimit = NEAR_M * scale
        val refs = HashMap<String, PointRef>()
        val at = HashMap<String, LatLon>()
        val edges = HashMap<String, MutableList<Pair<String, Double>>>()
        for (segment in segments) {
            val from = segment.fromPoint ?: continue
            val to = segment.toPoint ?: continue
            refs.putIfAbsent(from.key, from)
            refs.putIfAbsent(to.key, to)
            at.putIfAbsent(from.key, segment.from)
            at.putIfAbsent(to.key, segment.to)
            if (from.key == to.key) continue
            edges.getOrPut(from.key) { ArrayList() } += to.key to segment.lengthM
            edges.getOrPut(to.key) { ArrayList() } += from.key to segment.lengthM
        }
        if (seed.key !in at && fallbackAt != null) {
            refs[seed.key] = seed
            at[seed.key] = fallbackAt
        }
        val seedAt = at[seed.key] ?: return null

        // A proposal never reaches further than reachM, so everything outside
        // that window can be left out before any distance is computed. A whole
        // history has thousands of endpoints; a junction has dozens.
        val window = nearby(seedAt, at, reachM)

        val existing = places.firstOrNull { place -> place.points.any { it.key == seed.key } }
        val foreign = places.filter { it.id != existing?.id }
        val members = LinkedHashSet<String>()
        val carried = ArrayList<PointRef>()
        if (existing != null) {
            for (ref in existing.points) {
                if (ref.key in at) members += ref.key else carried += ref
            }
        }
        members += seed.key

        var grew = true
        while (grew && members.size < MAX_POINTS) {
            grew = false
            val snapshot = members.toList()
            for (key in snapshot) {
                val here = at[key] ?: continue
                val candidates = ArrayList<String>()
                edges[key]?.forEach { (other, length) ->
                    if (length <= edgeLimit) candidates += other
                }
                for (other in window) {
                    if (other == key) continue
                    if (Geo.distanceMeters(here, at.getValue(other)) <= nearLimit) candidates += other
                }
                for (other in candidates) {
                    if (other in members) continue
                    if (members.size >= MAX_POINTS) break
                    val otherAt = at[other] ?: continue
                    if (Geo.distanceMeters(seedAt, otherAt) > reachM) continue
                    if (foreign.any { place ->
                            Geo.distanceMeters(otherAt, place.at) <= place.radiusM
                        }
                    ) {
                        continue
                    }
                    members += other
                    grew = true
                }
            }
        }

        // The user gets the last word. Picks are applied after the automatic
        // rules so they can add a point no threshold would reach, or take one
        // back out that the rules swallowed. MAX_POINTS guards the automatic
        // chain, not an explicit list of points the user can see and count.
        for ((key, include) in manual) {
            if (key == seed.key) continue
            if (!include) {
                members -= key
                continue
            }
            val point = at[key] ?: continue
            if (foreign.any { Geo.distanceMeters(point, it.at) <= it.radiusM }) continue
            members += key
        }

        val located = members.mapNotNull { key -> at[key]?.let { key to it } }
            .map { (key, point) -> refs.getValue(key) to point }
        if (located.isEmpty()) return null
        val center = LatLon(
            located.map { it.second.lat }.average(),
            located.map { it.second.lon }.average(),
        )
        val radius = located.maxOf { (_, point) -> Geo.distanceMeters(center, point) }
        var span = 0.0
        for (i in located.indices) {
            for (j in i + 1 until located.size) {
                span = maxOf(span, Geo.distanceMeters(located[i].second, located[j].second))
            }
        }

        return PlaceDraft(
            seed = seed,
            center = center,
            members = located
                .map { (ref, point) ->
                    PlaceMember(
                        ref = ref,
                        at = point,
                        distanceM = Geo.distanceMeters(center, point),
                    )
                }
                .sortedBy { it.distanceM },
            spanM = span,
            radiusM = radius,
            existingPlaceId = existing?.id,
            carried = carried,
        )
    }

    /**
     * Whether a point that has just turned up belongs to [place].
     *
     * The stored radius says how big the junction is; it does not say that
     * everything in that circle is part of it — a 300 m interchange's circle
     * also covers the next crossing down the road. Membership is therefore
     * decided by the points already in the junction: a new point has to land
     * near one of them. That is the difference between a wide junction and a
     * wide brush, and it is what makes raising the reach safe.
     */
    fun adopts(place: Place, at: LatLon, memberAt: (PointRef) -> LatLon?): Boolean {
        if (Geo.distanceMeters(at, place.at) > place.radiusM) return false
        return place.points.any { member ->
            val here = memberAt(member) ?: return@any false
            Geo.distanceMeters(at, here) <= NEAR_M
        }
    }

    /** Keys whose point could still be pulled in, by a bounding box first. */
    private fun nearby(seed: LatLon, at: Map<String, LatLon>, reachM: Double): List<String> {
        val latitudeWindow = reachM / METERS_PER_DEGREE
        // Near the poles a longitude degree shrinks; the guard keeps the box
        // from exploding instead of dividing by almost zero.
        val cos = kotlin.math.cos(Math.toRadians(seed.lat)).coerceAtLeast(0.01)
        val longitudeWindow = latitudeWindow / cos
        return at.entries
            .filter { (_, point) ->
                kotlin.math.abs(point.lat - seed.lat) <= latitudeWindow &&
                    kotlin.math.abs(point.lon - seed.lon) <= longitudeWindow
            }
            .map { it.key }
    }

    private const val METERS_PER_DEGREE = 111_320.0
}
