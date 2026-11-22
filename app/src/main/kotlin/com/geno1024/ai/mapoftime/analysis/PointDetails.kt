package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.data.RoadNames
import com.geno1024.ai.mapoftime.data.Segment
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * What can be said about one small point inside a wide junction **without
 * asking the user to name it**.
 *
 * A 300 m interchange is twenty-odd OSM nodes, and giving each of them its own
 * name would bring back exactly the chore that [PlaceDrafts] exists to remove.
 * But they are not interchangeable either: once they all carry one name, every
 * node card says the same thing and every piece inside says "inside X". So we
 * derive two facts from data we already have:
 *
 * - where the point sits in the junction (north side, south-east corner…), and
 * - which roads actually meet there, as told by the segments that touch it.
 *   A road the user named is just as real as an OSM name, and offline it is
 *   the only one there is.
 *
 * Neither is stored, so re-analysis cannot make it stale. The junction name is
 * still the name; this is only the caption that tells dots apart.
 */
data class PointDetail(
    /** The point's side of the junction, e.g. `北侧`; null near the middle. */
    val bearing: String?,
    /** Distinct road names of the pieces meeting here, in a stable order. */
    val roads: List<String>,
) {
    /** `北侧 · 人民路 × 建国路`, or null when there is nothing to say. */
    val label: String? get() = listOfNotNull(bearing, roadText)
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }

    private val roadText: String? get() = RoadNames.join(roads)
}

object PointDetails {
    private val SECTORS = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

    /** A point this close to the middle has no meaningful "side". */
    private const val MIN_BEARING_M = 12.0

    /**
     * [centre] is the junction's centre — a saved place's, or the preview
     * draft's when the place does not exist yet. [placeName] is dropped from
     * the derived road list: it names the junction, not a road through it.
     *
     * [explicitRoads] are roads the user chose at this point. When present they
     * are authoritative — several roads make the crossing the user described —
     * and [touching] is only the fallback for a point not named yet.
     */
    fun of(
        point: LatLon,
        centre: LatLon,
        placeName: String? = null,
        touching: List<Segment> = emptyList(),
        explicitRoads: Collection<String> = emptyList(),
    ): PointDetail {
        val chosen = explicitRoads.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return PointDetail(
            bearing = bearing(point, centre),
            roads = if (chosen.isNotEmpty()) {
                chosen
            } else {
                touching.mapNotNull(::roadName).filter { it != placeName }.distinct()
            },
        )
    }

    /** The user's road name wins; OSM is the fallback when there is none. */
    private fun roadName(segment: Segment): String? =
        segment.userName?.trim()?.takeIf(String::isNotBlank)
            ?: segment.name?.trim()?.takeIf(String::isNotBlank)

    private fun bearing(point: LatLon, centre: LatLon): String? {
        val north = (point.lat - centre.lat) * METERS_PER_DEGREE
        // A longitude degree shrinks with latitude; without this the east-west
        // side would be wrong at any distance from the equator.
        val east = (point.lon - centre.lon) * METERS_PER_DEGREE *
            cos(Math.toRadians(centre.lat)).coerceAtLeast(0.01)
        if (hypot(north, east) < MIN_BEARING_M) return null
        // Add half a sector, then floor: each 45° bin spans its own centre,
        // so due north (0°) lands in 北 and 45° in 东北. Rounding would push
        // the 0.5 boundary up and mislabel every cardinal direction.
        val sector = floor((atan2(east, north) + PI / 8 + 2 * PI) % (2 * PI) / (PI / 4))
            .toInt() % 8
        return SECTORS[sector] + "侧"
    }

    private const val METERS_PER_DEGREE = 111_320.0
}
