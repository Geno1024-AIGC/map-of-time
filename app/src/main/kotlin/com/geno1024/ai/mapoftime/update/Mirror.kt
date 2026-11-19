package com.geno1024.ai.mapoftime.update

/**
 * A GitHub "prefix proxy": the target URL is appended after the prefix, e.g.
 *
 *     https://ghfast.top/https://github.com/owner/repo/releases/download/...
 *
 * These mirrors are third-party, community-run infrastructure with no SLA.
 * Domains come and go, so the selection is persisted, accepted as an arbitrary
 * typed prefix, and backed by a fallback list rather than fixed at the call
 * site. Only file transfers go through a mirror; release metadata is always
 * read from the GitHub API.
 */
data class Mirror(
    val label: String,
    val prefix: String,
) {
    /** Returns [url] unchanged for [Mirrors.DIRECT]. */
    fun wrap(url: String): String = if (prefix.isEmpty()) url else prefix.trimEnd('/') + "/" + url
}

object Mirrors {

    /** Direct GitHub. Usually the most reliable, but not always reachable. */
    val DIRECT = Mirror("GitHub", "")

    /** Only domains observed serving a release asset are listed. */
    val PRESETS: List<Mirror> = listOf(
        DIRECT,
        Mirror("ghfast.top", "https://ghfast.top"),
        Mirror("gh-proxy.com", "https://gh-proxy.com"),
        Mirror("ghproxy.net", "https://ghproxy.net"),
    )

    /** Accepts a known preset or an arbitrary user-typed prefix. */
    fun resolve(prefix: String): Mirror {
        val trimmed = prefix.trim()
        PRESETS.firstOrNull { it.prefix == trimmed }?.let { return it }
        return Mirror("自定义 / Custom", trimmed)
    }

    /** The chosen source first, then the fallbacks, with no duplicate URLs. */
    fun candidates(selectedPrefix: String): List<Mirror> = buildList {
        add(resolve(selectedPrefix))
        addAll(PRESETS)
    }.distinctBy { it.prefix }
}
