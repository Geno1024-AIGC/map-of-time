package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.data.Segment

/**
 * The stretch of road a segment belongs to: segments joined end to end without
 * a real turn, and without crossing into a differently named road.
 *
 * Naming one segment gives it the road's name; extending that one action to
 * the whole run is what keeps a road from being spelled a dozen ways. A gentle
 * bend stays in the run (roads curve), a junction arm does not (that is a
 * different road), and an already named neighbour is never silently taken over.
 */
object RoadRuns {
    /** A turn sharper than this starts a different road, not a bend in this one. */
    private const val MAX_TURN_DEGREES = 35.0

    /** Guard against a malformed graph sending the walk round a loop forever. */
    private const val MAX_SEGMENTS = 64

    fun contiguous(
        segments: List<Segment>,
        seedId: Long,
        maxTurnDegrees: Double = MAX_TURN_DEGREES,
    ): List<Segment> {
        val seed = segments.firstOrNull { it.id == seedId } ?: return emptyList()
        val pool = segments.filter { it.mode == seed.mode && it.id != seedId && compatible(it, seed) }
        val used = HashSet<Long>(listOf(seedId))

        val after = ArrayList<Segment>()
        var tail = seed
        while (after.size < MAX_SEGMENTS) {
            val found = next(pool, used, tail, atStart = false, maxTurnDegrees) ?: break
            after += found
            used += found.id
            tail = found
        }

        val before = ArrayList<Segment>()
        var head = seed
        while (before.size < MAX_SEGMENTS) {
            val found = next(pool, used, head, atStart = true, maxTurnDegrees) ?: break
            before += found
            used += found.id
            head = found
        }
        return before.asReversed() + seed + after
    }

    /** Would adding [candidate] overwrite a road name someone already chose? */
    private fun compatible(candidate: Segment, seed: Segment): Boolean {
        val current = candidate.userName?.trim()?.takeIf(String::isNotBlank) ?: return true
        return current == seed.userName?.trim()?.takeIf(String::isNotBlank)
    }

    /**
     * [atStart] looks back before the seed; otherwise we continue after it.
     * Both compare bearings in the direction of travel, so the test is the same
     * at either end: does the neighbour keep going the way we were heading?
     */
    private fun next(
        pool: List<Segment>,
        used: Set<Long>,
        current: Segment,
        atStart: Boolean,
        maxTurnDegrees: Double,
    ): Segment? {
        val here = endpoint(current, atStart)
        val heading = Geo.bearingDegrees(current.from, current.to)
        var best: Segment? = null
        var bestAngle = Double.MAX_VALUE
        for (candidate in pool) {
            if (candidate.id in used) continue
            if (endpoint(candidate, !atStart) != here) continue
            val angle = Geo.angleDifference(heading, Geo.bearingDegrees(candidate.from, candidate.to))
            if (angle <= maxTurnDegrees && angle < bestAngle) {
                best = candidate
                bestAngle = angle
            }
        }
        return best
    }

    private fun endpoint(segment: Segment, atStart: Boolean): String {
        val key = if (atStart) segment.fromKey else segment.toKey
        if (key != null) return key
        val at = if (atStart) segment.from else segment.to
        return "at:${(at.lat * 1e6).toLong()}:${(at.lon * 1e6).toLong()}"
    }
}
