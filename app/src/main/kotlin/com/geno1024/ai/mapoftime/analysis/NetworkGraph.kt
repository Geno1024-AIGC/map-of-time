package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.LatLon
import java.util.PriorityQueue
import kotlin.math.roundToLong

/**
 * One place the travelled segments meet, together with the segments that touch
 * it. A node is not just a dot: it is the identity two segments share, which is
 * exactly what makes the network a network instead of a pile of lines.
 */
data class NetworkNode(
    val key: String,
    /** The user's name for this point, when one was given. */
    val name: String?,
    val at: LatLon,
    val links: List<NetworkLink>,
) {
    /** How many segments meet here; a dead end has degree 1. */
    val degree: Int get() = links.size
}

/**
 * One edge as seen from one of its ends. Which end is being looked at
 * decides which direction's numbers apply: `forward` samples left the
 * edge's `from`, `backward` samples arrived at it.
 */
data class NetworkLink(
    val summary: EdgeSummary,
    /** True when the node holding this link is the edge's `from` end. */
    val fromHere: Boolean,
    /** The node at the far end, so the caller can name it without a second pass. */
    val otherKey: String,
) {
    /** The traversal that leaves the node with this link. */
    val outbound: DurationStats get() = if (fromHere) summary.forward else summary.backward

    /** The traversal that comes back to it. */
    val inbound: DurationStats get() = if (fromHere) summary.backward else summary.forward
}

/** One segment of a route between two chosen nodes. */
data class RouteHop(
    val fromKey: String,
    val toKey: String,
    val link: NetworkLink,
    /** Direction-specific typical time, or the best available fallback. */
    val typicalMs: Long,
    /** Some or all of this leg's samples were recorded the other way. */
    val estimated: Boolean,
)

/**
 * A path through the travelled network, with the time it typically takes.
 *
 * The total is the sum of per-segment medians, not the median of complete
 * trips: the app has never seen this whole path as one traversal.
 */
data class NetworkRoute(
    val fromKey: String,
    val toKey: String,
    val hops: List<RouteHop>,
) {
    val typicalMs: Long get() = hops.sumOf { it.typicalMs }
    val distanceM: Double get() = hops.sumOf { it.link.summary.edge.lengthM }
    val estimatedHops: Int get() = hops.count { it.estimated }

    /** From the start node, then the far end of each hop: one key per corner. */
    val nodeKeys: List<String> get() = listOf(fromKey).plus(hops.map { it.toKey })
}

/**
 * The edges the user has travelled, arranged by the junctions they share.
 * Pure data: no map, no database, so the shape can be unit-tested.
 */
data class NetworkGraph(
    val nodes: List<NetworkNode>,
    val edges: List<EdgeSummary>,
    /** How many groups of nodes are not connected to each other. */
    val parts: Int,
) {
    /** Compatibility while callers migrate from segment terminology. */
    val segments: List<EdgeSummary> get() = edges

    fun node(key: String?): NetworkNode? =
        if (key == null) null else nodes.firstOrNull { it.key == key }

    /**
     * The lowest-typical-time path between two nodes, or null when the chosen
     * nodes are not connected by the segments we have actually walked.
     *
     * A hop with no samples in the needed direction falls back to the other
     * direction, then to the pooled median. It is still a usable connection,
     * but [RouteHop.estimated] tells the caller the direction was guessed.
     */
    fun route(fromKey: String, toKey: String): NetworkRoute? {
        if (fromKey == toKey) return NetworkRoute(fromKey, toKey, emptyList())
        if (node(fromKey) == null || node(toKey) == null) return null

        val byKey = nodes.associateBy { it.key }
        val best = HashMap<String, Long>().apply { put(fromKey, 0) }
        val previous = HashMap<String, RouteHop>()
        val settled = HashSet<String>()
        val queue = PriorityQueue<Pair<String, Long>>(compareBy { it.second })
        queue.add(fromKey to 0L)

        while (queue.isNotEmpty()) {
            val (key, cost) = queue.remove()
            if (!settled.add(key) || cost != best[key] || key == toKey) continue
            val node = byKey[key] ?: continue
            for (link in node.links) {
                val hop = hopFrom(key, link) ?: continue
                val next = cost + hop.typicalMs
                val old = best[link.otherKey]
                if (old == null || next < old) {
                    best[link.otherKey] = next
                    previous[link.otherKey] = hop
                    queue.add(link.otherKey to next)
                }
            }
        }

        if (toKey !in best) return null
        val hops = ArrayList<RouteHop>()
        var cursor = toKey
        while (cursor != fromKey) {
            val hop = previous[cursor] ?: return null
            hops.add(hop)
            cursor = hop.fromKey
        }
        return NetworkRoute(fromKey, toKey, hops.asReversed())
    }

    private fun hopFrom(fromKey: String, link: NetworkLink): RouteHop? {
        val (ms, estimated) = when {
            link.outbound.count > 0 -> link.outbound.typicalMs to false
            link.inbound.count > 0 -> link.inbound.typicalMs to true
            link.summary.combined.count > 0 -> link.summary.combined.typicalMs to true
            else -> return null
        }
        // A zero-duration edge is an artefact, not a free connection: letting
        // it into a route would produce a believable-looking zero total.
        if (ms <= 0) return null
        return RouteHop(fromKey, link.otherKey, link, ms, estimated)
    }

    companion object {
        val EMPTY = NetworkGraph(emptyList(), emptyList(), 0)
    }
}

object NetworkGraphs {
    /**
     * Groups [summaries] by shared endpoint identity. Edges without a
     * parseable point key (old `mix:` rows) fall back to their coordinates, so
     * they still connect to something instead of each becoming an island.
     */
    fun build(summaries: List<EdgeSummary>): NetworkGraph {
        if (summaries.isEmpty()) return NetworkGraph.EMPTY

        val nodes = LinkedHashMap<String, Accumulator>()
        val parent = HashMap<String, String>()

        fun rootOf(key: String): String {
            var node = key
            while (true) {
                val next = parent[node] ?: return node
                if (next == node) return node
                node = next
            }
        }

        fun union(a: String, b: String) {
            val ra = rootOf(a)
            val rb = rootOf(b)
            if (ra != rb) parent[ra] = rb
        }

        for (summary in summaries) {
            val edge = summary.edge
            val fromKey = nodeKey(edge.fromKey, edge.from)
            val toKey = nodeKey(edge.toKey, edge.to)
            nodes.getOrPut(fromKey) { Accumulator(edge.from) }.also {
                if (it.name == null) it.name = edge.fromName
            }
            nodes.getOrPut(toKey) { Accumulator(edge.to) }.also {
                if (it.name == null) it.name = edge.toName
            }
            // A degenerate edge whose two ends collapsed onto one identity
            // cannot add a link; drawing it twice would inflate the degree.
            if (fromKey == toKey) continue

            nodes.getValue(fromKey).links += NetworkLink(summary, fromHere = true, otherKey = toKey)
            nodes.getValue(toKey).links += NetworkLink(summary, fromHere = false, otherKey = fromKey)
            union(fromKey, toKey)
        }

        val parts = nodes.keys.map(::rootOf).toSet().size
        return NetworkGraph(
            nodes = nodes.map { (key, acc) ->
                NetworkNode(key = key, name = acc.name, at = acc.at, links = acc.links)
            }.sortedWith(
                compareByDescending<NetworkNode> { it.degree }.thenBy { it.key },
            ),
            edges = summaries,
            parts = parts,
        )
    }

    /**
     * The identity shared by everything meeting at [at]. A parseable point key
     * is the real identity; coordinates to six decimals (about 0.1 m) are the
     * fallback for rows written before endpoints were stored.
     */
    fun nodeKey(rawKey: String?, at: LatLon): String =
        rawKey ?: "at:" + (at.lat * 1e6).roundToLong() + ":" + (at.lon * 1e6).roundToLong()

    private class Accumulator(val at: LatLon) {
        var name: String? = null
        val links = ArrayList<NetworkLink>()
    }
}
