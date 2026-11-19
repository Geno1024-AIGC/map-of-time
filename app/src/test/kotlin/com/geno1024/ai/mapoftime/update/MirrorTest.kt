package com.geno1024.ai.mapoftime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTest {

    private val url = "https://github.com/Geno1024-AIGC/map-of-time/releases/download/canary/app.apk"

    @Test
    fun directLeavesTheGithubUrlUntouched() {
        assertEquals(url, Mirrors.DIRECT.wrap(url))
    }

    @Test
    fun aMirrorPrefixesTheWholeGithubUrl() {
        val mirror = Mirrors.PRESETS.first { it.prefix == "https://ghfast.top" }
        assertEquals("https://ghfast.top/$url", mirror.wrap(url))
        // A trailing slash in the stored prefix must not produce a double slash.
        assertEquals("https://ghfast.top/$url", mirror.copy(prefix = "https://ghfast.top/").wrap(url))
    }

    @Test
    fun resolveKeepsKnownPresetsAndAcceptsCustomOnes() {
        assertEquals("ghfast.top", Mirrors.resolve(" https://ghfast.top ").label)
        val custom = Mirrors.resolve(" https://example.org/proxy ")
        assertEquals("https://example.org/proxy", custom.prefix)
        assertEquals(url, custom.wrap(url).substringAfter("https://example.org/proxy/"))
    }

    @Test
    fun theChosenSourceIsTriedFirstAndDeduplicated() {
        val candidates = Mirrors.candidates("https://gh-proxy.com")
        assertEquals("https://gh-proxy.com", candidates.first().prefix)
        assertEquals(
            Mirrors.PRESETS.map { it.prefix }.filter { it != "https://gh-proxy.com" },
            candidates.drop(1).map { it.prefix },
        )
        assertEquals(candidates.map { it.prefix }.toSet().size, candidates.size)

        val directFirst = Mirrors.candidates("")
        assertEquals(Mirrors.DIRECT, directFirst.first())
        assertEquals(Mirrors.PRESETS.size, directFirst.size)
    }

    @Test
    fun aCustomSourceStillFallsBackThroughEveryPreset() {
        val candidates = Mirrors.candidates("https://custom.example")
        assertTrue(candidates.first().prefix == "https://custom.example")
        assertEquals(Mirrors.PRESETS.size + 1, candidates.size)
    }
}
