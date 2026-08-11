package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `GENRE` entry cached before genre tags carried their curated marking cannot say which of its
 * names came from a controlled vocabulary, and GENRE's TTL is 90 days. It reads as a miss so the
 * write-back heals it, the same way an entry answering nothing already does.
 */
class CuratedGenreCacheHealingTest {

    private val request = EnrichmentRequest.forArtist("Coldplay")

    private fun genreResult(provider: String, curated: Boolean?) = EnrichmentResult.Success(
        type = EnrichmentType.GENRE,
        data = EnrichmentData.Metadata(
            genres = listOf("alternative rock"),
            genreTags = listOf(GenreTag("alternative rock", 0.4f, listOf(provider), curated = curated)),
        ),
        provider = provider,
        confidence = 1.0f,
    )

    private fun engine(cache: FakeEnrichmentCache, provider: FakeProvider) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        cache,
        EnrichmentConfig(enableIdentityResolution = false),
    )

    private fun provider() = FakeProvider(
        id = "fresh",
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
    ).also { it.givenResult(EnrichmentType.GENRE, genreResult("fresh", curated = true)) }

    private suspend fun FakeEnrichmentCache.seed(result: EnrichmentResult.Success) {
        put(entityKeyFor(request, EnrichmentType.GENRE), EnrichmentType.GENRE, result, ttlMs = Long.MAX_VALUE)
    }

    @Test
    fun `a cached genre entry with no curated marking is refetched`() = runTest {
        // Given - an unexpired GENRE entry whose tags predate the curated marking
        val cache = FakeEnrichmentCache()
        cache.seed(genreResult("stale", curated = null))
        val provider = provider()

        // When - asking for GENRE
        val results = engine(cache, provider).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the provider ran and its answer, not the cached one, is returned
        assertEquals(listOf(request to EnrichmentType.GENRE), provider.enrichCalls)
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(listOf("fresh"), (success.data as EnrichmentData.Metadata).genreTags!!.single().sources)
    }

    @Test
    fun `the refetched entry replaces the unmarked one`() = runTest {
        // Given - the same unmarked entry, and a provider that now states the marking
        val cache = FakeEnrichmentCache()
        cache.seed(genreResult("stale", curated = null))

        // When - enriching once, so the write-back runs
        engine(cache, provider()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the stored entry is marked, so the next call is a hit rather than a second refetch
        val stored = cache.stored.values.filter { it.type == EnrichmentType.GENRE }
        assertTrue(stored.isNotEmpty())
        assertTrue(
            stored.all { entry ->
                (entry.data as EnrichmentData.Metadata).genreTags!!.all { it.curated != null }
            },
        )
    }

    @Test
    fun `a cached genre entry that states no curated genres is served from cache`() = runTest {
        // Given - an entry a current build wrote for an entity with only community tags
        val cache = FakeEnrichmentCache()
        cache.seed(genreResult("cached", curated = false))
        val provider = provider()

        // When - asking for GENRE
        val results = engine(cache, provider).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - no provider call: "not curated" is an answer, and refetching it would never end
        assertEquals(emptyList<Pair<EnrichmentRequest, EnrichmentType>>(), provider.enrichCalls)
        assertEquals("cached", (results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success).provider)
    }
}
