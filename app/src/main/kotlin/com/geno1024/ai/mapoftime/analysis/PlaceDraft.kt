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
 */
object PlaceDrafts {
    /** A piece of road this short is inside a junction, not between two. */
    const val SHORT_EDGE_M = 45.0

    /** Two points this close are the same knot even without a piece between them. */
    const val NEAR_M = 50.0

    /** No proposal reaches further than this from the point that was tapped. */
    const val SPAN_M = 150.0

    /** A gate against a pathological chain; a real junction is well under this. */
    const val MAX_POINTS = 24

    /** Adoption needs a floor, or a one-point junction would swallow nothing. */
    const val MIN_ADOPT_M = 25.0

    fun draft(
        seed: PointRef,
        segments: List<Segment>,
        places: List<Place> = emptyList(),
        /** Where the seed is, when no stored segment mentions it. */
        fallbackAt: LatLon? = null,
    ): PlaceDraft? {
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

        // A proposal never reaches further than SPAN_M, so everything outside
        // that window can be left out before any distance is computed. A whole
        // history has thousands of endpoints; a junction has dozens.
        val window = nearby(seedAt, at)

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
                    if (length <= SHORT_EDGE_M) candidates += other
                }
                for (other in window) {
                    if (other == key) continue
                    if (Geo.distanceMeters(here, at.getValue(other)) <= NEAR_M) candidates += other
                }
                for (other in candidates) {
                    if (other in members) continue
                    if (members.size >= MAX_POINTS) break
                    val otherAt = at[other] ?: continue
                    if (Geo.distanceMeters(seedAt, otherAt) > SPAN_M) continue
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

    /** Keys whose point could still be pulled in, by a bounding box first. */
    private fun nearby(seed: LatLon, at: Map<String, LatLon>): List<String> {
        val latitudeWindow = SPAN_M / METERS_PER_DEGREE
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
