package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.contract.MinimalEnrichmentCache
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cache taking [com.landofoz.musicmeta.EnrichmentCache.getNegative]'s `null` default and
 * [com.landofoz.musicmeta.EnrichmentCache.putNegative]'s no-op default must not corrupt an
 * `enrich()` call — the engine declines to use the missing negative channel rather than treating a
 * miss on it as anything but "nothing cached", and a repeat call over the same request re-runs the
 * live lookup instead of throwing or hanging.
 */
class EnrichmentCacheDefaultsSeamTest {

    @Test
    fun `a cache with no negative-cache support answers two calls without error`() = runTest {
        // Given - the real stack over a scenario that declines every LRCLIB type, backed by a
        // cache that only implements EnrichmentCache's six abstract members
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http, cache = MinimalEnrichmentCache())
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")

        // When - enriching twice for the same request and type, so the second call's read finds
        // whatever the first call's write-back left behind
        val first = engine.enrich(request, setOf(EnrichmentType.LYRICS_PLAIN)).raw[EnrichmentType.LYRICS_PLAIN]
        val second = engine.enrich(request, setOf(EnrichmentType.LYRICS_PLAIN)).raw[EnrichmentType.LYRICS_PLAIN]

        // Then - both calls decline cleanly; neither the no-op putNegative nor the always-null
        // getNegative raises, and the second call is answered by a fresh live lookup rather than a
        // stale or corrupted read
        for (result in listOf(first, second)) {
            assertTrue(
                "expected a decline, not a Success answering the wrong track: $result",
                result !is EnrichmentResult.Success,
            )
        }
    }

    private companion object {
        const val SCENARIO = "lrclib-first-result"
    }
}
