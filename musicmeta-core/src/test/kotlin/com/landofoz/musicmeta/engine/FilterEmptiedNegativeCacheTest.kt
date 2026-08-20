package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Emptiness a `CatalogFilterMode` produces is a fact about the local catalog, never a provider's
 * own answer — no path that reaches `NotFound` by filtering a `Success` down to nothing is
 * negative-cache eligible, whether that `Success` came from a live call this run, a stale
 * substitute, or a fresh cache hit re-filtered on a later call. The stale-substitute case is
 * vetoed by `RunSession.staleDerived` (set when the substitution itself fires) rather than
 * `filterEmptied`; the behaviour pinned here is the same either way. See `docs/pitfalls.md`,
 * "Traps in the pipeline".
 */
class FilterEmptiedNegativeCacheTest {

    private val req = EnrichmentRequest.forArtist("Radiohead")

    private fun similarArtists(vararg names: String): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(names.map { SimilarArtist(name = it, matchScore = 0.9f) }),
            provider = "stale-provider",
            confidence = 0.9f,
        )

    private val allUnavailable = CatalogProvider { queries -> queries.map { CatalogMatch(available = false, source = "test") } }

    @Test
    fun `a NotFound produced by re-filtering a stale substitute is not negative-cached`() = runTest {
        // Given - STALE_IF_ERROR with an AVAILABLE_ONLY catalog filter that empties every item,
        // a provider that fails live, and a stale cache entry old enough to have expired
        val cache = FakeEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.SIMILAR_ARTISTS)
        cache.expiredStore["$key:${EnrichmentType.SIMILAR_ARTISTS}"] = similarArtists("Thom Yorke")
        val failingProvider = FakeProvider(
            id = "failing",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also {
            it.givenResult(EnrichmentType.SIMILAR_ARTISTS, EnrichmentResult.Error(EnrichmentType.SIMILAR_ARTISTS, "failing", "API down"))
        }
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            cacheMode = CacheMode.STALE_IF_ERROR,
            catalogProvider = allUnavailable,
            catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
        )
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(failingProvider)), cache, config)

        // When - enriching the type whose live call fails and whose stale substitute filters to empty
        val results = engine.enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the caller sees NotFound, but it must not be written as a negative-cache entry
        assertNotNull(
            "the substitute filtering to empty should still surface as NotFound",
            results.raw[EnrichmentType.SIMILAR_ARTISTS] as? EnrichmentResult.NotFound,
        )
        assertNull(cache.negativeStored["$key:${EnrichmentType.SIMILAR_ARTISTS}"])
    }

    @Test
    fun `a genuine live NotFound still negative-caches under STALE_IF_ERROR and AVAILABLE_ONLY`() = runTest {
        // Given - the same STALE_IF_ERROR plus AVAILABLE_ONLY config, but the provider itself
        // answers NotFound live rather than erroring, so there is no stale substitute at all
        val cache = FakeEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.SIMILAR_ARTISTS)
        val provider = FakeProvider(
            id = "provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        )
        // FakeProvider answers NotFound by default when no result is configured for the type.
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            cacheMode = CacheMode.STALE_IF_ERROR,
            catalogProvider = allUnavailable,
            catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
        )
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, config)

        // When - enriching the type whose live call is a genuine NotFound
        engine.enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - a real "providers had nothing" answer is still negative-cache eligible
        assertNotNull(cache.negativeStored["$key:${EnrichmentType.SIMILAR_ARTISTS}"])
    }

    @Test
    fun `a live Success emptied by AVAILABLE_ONLY is not negative-cached`() = runTest {
        // Given - a plain live call (no STALE_IF_ERROR involved) whose provider answers Success,
        // but every item the catalog reports unavailable, so filtering empties it to NotFound
        val cache = FakeEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.SIMILAR_ARTISTS)
        val provider = FakeProvider(
            id = "provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Thom Yorke")) }
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            catalogProvider = allUnavailable,
            catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
        )
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, config)

        // When - enriching the type whose live Success the catalog filter empties
        val results = engine.enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the caller sees NotFound, but the emptiness is the catalog's, not the provider's
        assertNotNull(
            "catalog filtering to empty should still surface as NotFound",
            results.raw[EnrichmentType.SIMILAR_ARTISTS] as? EnrichmentResult.NotFound,
        )
        assertNull(cache.negativeStored["$key:${EnrichmentType.SIMILAR_ARTISTS}"])
    }

    @Test
    fun `a fresh cache hit emptied by AVAILABLE_ONLY on re-filter is not negative-cached`() = runTest {
        // Given - SIMILAR_ARTISTS already sits fresh in the positive cache, and this call also
        // asks for an uncached type, so the run is a partial cache hit that re-filters the cached
        // type through settle()/finalizeResult rather than taking enrichProgressive's full-cache-hit
        // fast path
        val cache = FakeEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.SIMILAR_ARTISTS)
        cache.stored["$key:${EnrichmentType.SIMILAR_ARTISTS}"] = similarArtists("Thom Yorke")
        val provider = FakeProvider(
            id = "provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
        )
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            catalogProvider = allUnavailable,
            catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
        )
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, config)

        // When - enriching both the cached type and an uncached one in the same call
        val results = engine.enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.ARTIST_BIO))

        // Then - the cached type's re-filtered emptiness must not be written as a negative entry
        assertNotNull(
            "the cache-hit type's re-filtered emptiness should still surface as NotFound",
            results.raw[EnrichmentType.SIMILAR_ARTISTS] as? EnrichmentResult.NotFound,
        )
        assertNull(cache.negativeStored["$key:${EnrichmentType.SIMILAR_ARTISTS}"])
    }
}
