package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedCacheBehaviorTest {
    private val type = EnrichmentType.ALBUM_ART
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `an expired pinned result is served without a provider call`() = runTest {
        // Given - an expired manually selected value in the default cache
        var now = 1_000L
        val cache = InMemoryEnrichmentCache(clock = { now })
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED, ttlMs = 1)
        cache.markManuallySelected(key, type)
        now += 2
        val provider = provider("fresh")
        val engine = engine(cache, provider)

        // When - enriching after its ordinary TTL elapsed
        val result = engine.enrich(request, setOf(type)).raw.getValue(type) as EnrichmentResult.Success

        // Then - the pinned selection remains the served result and no refresh occurs
        assertEquals("chosen", result.provider)
        assertEquals(0, provider.enrichCalls.size)
    }

    @Test fun `force refresh removes a pin before fetching and writing fresh data`() = runTest {
        // Given - a pinned cached choice and a provider with a replacement
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(key, type)
        val provider = provider("fresh")
        val engine = engine(cache, provider)

        // When - forceRefresh requests a replacement
        val result = engine.enrich(request, setOf(type), forceRefresh = true).raw.getValue(type) as EnrichmentResult.Success

        // Then - explicit refresh clears the pin and the replacement persists
        assertEquals("fresh", result.provider)
        assertFalse(cache.isManuallySelected(key, type))
        assertEquals("fresh", cache.get(key, type)?.result?.provider)
    }

    @Test fun `a marker-only pin allows its first positive fill`() = runTest {
        // Given - a selected key with no stored positive data
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.markManuallySelected(key, type)
        val provider = provider("fresh")

        // When - enrichment obtains its first positive answer
        engine(cache, provider).enrich(request, setOf(type))

        // Then - the first value is stored while the pin remains present
        assertEquals("fresh", cache.get(key, type)?.result?.provider)
        assertEquals(true, cache.isManuallySelected(key, type))
    }

    @Test fun `a pinned key rejects both positive and negative direct write back`() = runTest {
        // Given - a pinned positive cache entry
        val cache = InMemoryEnrichmentCache()
        cache.put("key", type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected("key", type)

        // When - positive then negative automatic writes race the selected value
        cache.put("key", type, art("replacement"), CanonicalStatus.RESOLVED)
        cache.putNegative("key", type, EnrichmentResult.NotFound(type, "provider"), CanonicalStatus.RESOLVED, 1_000)

        // Then - neither write replaces or shadows the selected positive value
        assertEquals("chosen", cache.get("key", type)?.result?.provider)
        assertNull(cache.getNegative("key", type))
    }

    @Test fun `unsafe pinned Wikipedia artwork is withheld without clearing its pin`() = runTest {
        // Given - a pinned legacy Wikipedia image with no file attribution
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.put(key, type, art("wikipedia"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(key, type)
        val provider = provider("fresh")

        // When - enrichment reads the unsafe pinned entry
        val result = engine(cache, provider).enrich(request, setOf(type)).raw.getValue(type) as EnrichmentResult.Success

        // Then - it is withheld, fresh data is served, and explicit invalidation is still required to clear the pin
        assertEquals("fresh", result.provider)
        assertEquals(true, cache.isManuallySelected(key, type))
        assertEquals("wikipedia", cache.getIncludingExpired(key, type)?.result?.provider)
    }

    private fun engine(cache: InMemoryEnrichmentCache, provider: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

    private fun provider(name: String) = FakeProvider(
        id = name,
        capabilities = listOf(ProviderCapability(type, 100)),
    ).also { it.givenResult(type, art(name)) }

    private fun art(provider: String) = EnrichmentResult.Success(
        type,
        EnrichmentData.Artwork("https://example.test/$provider.jpg"),
        provider,
        0.9f,
    )
}
