package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.LatLon
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
 * One segment as seen from one of its ends. Which end is being looked at
 * decides which direction's numbers apply: `forward` samples left the
 * segment's `from`, `backward` samples arrived at it.
 */
data class NetworkLink(
    val summary: SegmentSummary,
    /** True when the node holding this link is the segment's `from` end. */
    val fromHere: Boolean,
    /** The node at the far end, so the caller can name it without a second pass. */
    val otherKey: String,
) {
    /** The traversal that leaves the node with this link. */
    val outbound: DurationStats get() = if (fromHere) summary.forward else summary.backward

    /** The traversal that comes back to it. */
    val inbound: DurationStats get() = if (fromHere) summary.backward else summary.forward
}

/**
 * The segments the user has travelled, arranged by the junctions they share.
 * Pure data: no map, no database, so the shape can be unit-tested.
 */
data class NetworkGraph(
    val nodes: List<NetworkNode>,
    val segments: List<SegmentSummary>,
    /** How many groups of nodes are not connected to each other. */
    val parts: Int,
) {
    fun node(key: String?): NetworkNode? =
        if (key == null) null else nodes.firstOrNull { it.key == key }

    companion object {
        val EMPTY = NetworkGraph(emptyList(), emptyList(), 0)
    }
}

object NetworkGraphs {
    /**
     * Groups [summaries] by shared endpoint identity. Segments without a
     * parseable point key (old `mix:` rows) fall back to their coordinates, so
     * they still connect to something instead of each becoming an island.
     */
    fun build(summaries: List<SegmentSummary>): NetworkGraph {
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
            val segment = summary.segment
            val fromKey = nodeKey(segment.fromKey, segment.from)
            val toKey = nodeKey(segment.toKey, segment.to)
            nodes.getOrPut(fromKey) { Accumulator(segment.from) }.also {
                if (it.name == null) it.name = segment.fromName
            }
            nodes.getOrPut(toKey) { Accumulator(segment.to) }.also {
                if (it.name == null) it.name = segment.toName
            }
            // A degenerate segment whose two ends collapsed onto one identity
            // cannot add an edge; drawing it twice would inflate the degree.
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
            segments = summaries,
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
