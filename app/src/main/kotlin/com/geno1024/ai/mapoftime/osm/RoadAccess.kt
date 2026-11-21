package com.geno1024.ai.mapoftime.osm

import com.geno1024.ai.mapoftime.core.TripMode

/**
 * Whose network an OSM junction belongs to. A zebra crossing where a footway
 * meets a road is a real crossing for someone on foot, but to a car it is just
 * a marking on the road it is driving along — not a reason to end a segment.
 *
 * Points themselves stay shared across modes: only the junctions used to cut
 * a leg depend on who is travelling.
 */
enum class Traveler { WALK, VEHICLE }

object RoadAccess {
    /**
     * Highway values a car (or a bike on the road) cannot drive along. They
     * still count as arms of a pedestrian junction. `cycleway` is deliberately
     * absent: bikes use it, and bikes share the vehicle road network here.
     */
    private val WALK_ONLY = setOf(
        "footway",
        "path",
        "pedestrian",
        "steps",
        "sidewalk",
        "crossing",
    )

    fun of(mode: TripMode): Traveler =
        if (mode == TripMode.WALK) Traveler.WALK else Traveler.VEHICLE

    /** Does a vehicle travel along this way? */
    fun vehicleWay(highway: String): Boolean = highway !in WALK_ONLY
}
