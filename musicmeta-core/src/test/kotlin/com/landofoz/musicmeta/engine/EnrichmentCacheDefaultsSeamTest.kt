package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cache that explicitly declines stale serving and the negative channel must not corrupt an
 * `enrich()` call — the engine treats a miss on either as nothing more than "nothing cached", and
 * a repeat call over the same request re-runs the live lookup instead of throwing or hanging.
 */
class EnrichmentCacheDefaultsSeamTest {

    @Test
    fun `a cache with no negative-cache support answers two calls without error`() = runTest {
        // Given - the real stack over a scenario that declines every LRCLIB type, backed by a
        // cache that explicitly declines stale serving and the negative channel
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http, cache = OptOutEnrichmentCache())
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")

        // When - enriching twice for the same request and type, so the second call's read finds
        // whatever the first call's write-back left behind
        val first = engine.enrich(request, setOf(EnrichmentType.LYRICS_PLAIN)).raw[EnrichmentType.LYRICS_PLAIN]
        val second = engine.enrich(request, setOf(EnrichmentType.LYRICS_PLAIN)).raw[EnrichmentType.LYRICS_PLAIN]

        // Then - both calls decline cleanly; neither the no-op putNegative nor the always-null
        // getNegative raises, and the second call is answered again rather than a stale or
        // corrupted read
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

/**
 * An [EnrichmentCache] that implements the interface in full and explicitly declines stale
 * serving and the negative channel. Single-purpose fixture for [EnrichmentCacheDefaultsSeamTest];
 * `private` because it has no other consumer.
 */
private class OptOutEnrichmentCache : EnrichmentCache {
    private val entries = mutableMapOf<String, Pair<EnrichmentResult.Success, CanonicalStatus>>()
    private val manualSelections = mutableSetOf<String>()

    private fun key(entityKey: String, type: EnrichmentType) = "$entityKey:$type"

    override suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? =
        entries[key(entityKey, type)]?.let { (result, status) -> CacheEnvelope(result, status) }

    override suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? =
        null

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        entries[key(entityKey, type)] = result to canonicalStatus
    }

    override suspend fun getNegative(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.NotFound>? =
        null

    override suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        // Explicit no-op: this fixture declines negative caching.
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        if (type != null) {
            entries.remove(key(entityKey, type))
            manualSelections.remove(key(entityKey, type))
        } else {
            entries.keys.removeAll { it.startsWith("$entityKey:") }
            manualSelections.removeAll { it.startsWith("$entityKey:") }
        }
    }

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean =
        key(entityKey, type) in manualSelections

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        manualSelections.add(key(entityKey, type))
    }

    override suspend fun clear() {
        entries.clear()
        manualSelections.clear()
    }
}
