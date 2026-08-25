package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A type that is both requested directly and a dependency of an uncached composite must settle
 * from cache once, not settle from cache and then be re-resolved upstream by the composite's
 * dependency closure — the second resolution is an upstream spend the cache already paid for, and
 * its write-back overwrites the cached entry.
 */
class CompositeCachedDependencyTest {

    private val request = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
        name = "Radiohead",
    )

    private val cachedDiscography = EnrichmentResult.Success(
        type = EnrichmentType.ARTIST_DISCOGRAPHY,
        data = EnrichmentData.Discography(listOf(DiscographyAlbum("Cached", "2020"))),
        provider = "cached-provider",
        confidence = 1f,
    )

    private fun discographyProvider() = FakeProvider(
        id = "discography",
        capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
    ).also {
        it.givenResult(
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentResult.Success(
                EnrichmentType.ARTIST_DISCOGRAPHY,
                EnrichmentData.Discography(listOf(DiscographyAlbum("Fresh", "2026"))),
                "discography",
                1f,
            ),
        )
    }

    private suspend fun cacheWithDiscography(): InMemoryEnrichmentCache {
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.ARTIST_DISCOGRAPHY)
        cache.put(key, EnrichmentType.ARTIST_DISCOGRAPHY, cachedDiscography, CanonicalStatus.RESOLVED, 60_000)
        return cache
    }

    @Test
    fun `a cache-hit dependency requested beside its uncached composite is not re-resolved`() = runTest {
        // Given - a cached discography, and a request for the timeline composite plus the
        // discography it depends on
        val cache = cacheWithDiscography()
        val discography = discographyProvider()
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(discography)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - enriching both types in one call
        val result = engine.enrich(
            request,
            setOf(EnrichmentType.ARTIST_TIMELINE, EnrichmentType.ARTIST_DISCOGRAPHY),
        )

        // Then - the discography settles from cache alone: the provider is never asked, the caller
        // sees the cached value, the composite still synthesizes, and the cache entry survives
        assertEquals(0, discography.enrichCalls.size)
        assertEquals(
            "cached-provider",
            (result.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.Success).provider,
        )
        assertEquals(
            "timeline_synthesizer",
            (result.raw.getValue(EnrichmentType.ARTIST_TIMELINE) as EnrichmentResult.Success).provider,
        )
        val key = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.ARTIST_DISCOGRAPHY)
        assertEquals(
            "cached-provider",
            (cache.get(key, EnrichmentType.ARTIST_DISCOGRAPHY)!!.result as EnrichmentResult.Success).provider,
        )
    }

    @Test
    fun `forceRefresh still resolves a cached dependency upstream`() = runTest {
        // Given - the same cached discography and the same two-type request
        val cache = cacheWithDiscography()
        val discography = discographyProvider()
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(discography)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - enriching with forceRefresh
        val result = engine.enrich(
            request,
            setOf(EnrichmentType.ARTIST_TIMELINE, EnrichmentType.ARTIST_DISCOGRAPHY),
            forceRefresh = true,
        )

        // Then - the cache is bypassed and the provider answers once
        assertEquals(1, discography.enrichCalls.size)
        assertEquals(
            "discography",
            (result.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.Success).provider,
        )
    }
}
