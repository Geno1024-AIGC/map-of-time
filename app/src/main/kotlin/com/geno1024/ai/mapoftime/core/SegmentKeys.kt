package com.geno1024.ai.mapoftime.core

/**
 * Stable identities for a road piece.
 *
 * The endpoint pair is the identity, sorted so that walking a street in either
 * direction lands on the same segment; the traversal direction is recorded per
 * sample instead. OSM ids and local cluster ids never collide because the
 * prefix says which kind of endpoint it is.
 */
object SegmentKeys {
    fun osm(a: Long, b: Long): String = "osm:${minOf(a, b)}-${maxOf(a, b)}"

    fun geo(a: Long, b: Long): String = "geo:${minOf(a, b)}-${maxOf(a, b)}"

    /** `+1` when travelling from the lower id to the higher one. */
    fun direction(from: Long, to: Long): Int = if (from <= to) 1 else -1
}
