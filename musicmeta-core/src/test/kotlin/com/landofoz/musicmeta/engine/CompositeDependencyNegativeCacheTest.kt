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
 * A [CompositeSynthesizer] reads its dependencies through [SettlementBoard.await], which hands it
 * each dependency's finalized value — a `STALE_IF_ERROR` substitute or a catalog-filter-emptied
 * `NotFound` — with no marker of where that value came from. A synthesizer that answers `NotFound`
 * over such a dependency must not leave its own composite type negative-cache eligible: that
 * `NotFound` describes a stale snapshot or the local catalog one layer removed, never this call's
 * providers. One tainted dependency among fresh ones is enough — the composite's answer still
 * rests on it. See `docs/pitfalls.md`, "Traps in the pipeline".
 */
class CompositeDependencyNegativeCacheTest {

    private val req = EnrichmentRequest.forArtist("Radiohead")

    /** Always synthesizes `NotFound`, regardless of what its dependency resolved to. */
    private class AlwaysEmptySynthesizer(
        override val type: EnrichmentType,
        override val dependencies: Set<EnrichmentType>,
    ) : CompositeSynthesizer {
        override fun synthesize(
            resolved: Map<EnrichmentType, EnrichmentResult>,
            identityResult: EnrichmentResult?,
            request: EnrichmentRequest,
        ): EnrichmentResult = EnrichmentResult.NotFound(type, "no_data")
    }

    private fun discography(): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_DISCOGRAPHY,
            data = EnrichmentData.Discography(listOf(DiscographyAlbum(title = "OK Computer", year = "1997"))),
            provider = "stale-provider",
            confidence = 0.9f,
        )

    @Test
    fun `a composite synthesized NotFound over a stale-substituted dependency is not negative-cached`() = runTest {
        // Given - STALE_IF_ERROR, a dependency whose live call fails but has a stale cache entry
        // (so it arrives at the synthesizer as a stale Success substitute), and a synthesizer that
        // always answers NotFound regardless of what it was handed
        val cache = FakeEnrichmentCache()
        val depKey = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ARTIST_DISCOGRAPHY)
        cache.expiredStore["$depKey:${EnrichmentType.ARTIST_DISCOGRAPHY}"] = discography()
        val failingProvider = FakeProvider(
            id = "failing",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.ARTIST_DISCOGRAPHY,
                EnrichmentResult.Error(EnrichmentType.ARTIST_DISCOGRAPHY, "failing", "API down"),
            )
        }
        val synthesizer = AlwaysEmptySynthesizer(
            type = EnrichmentType.ARTIST_TIMELINE,
            dependencies = setOf(EnrichmentType.ARTIST_DISCOGRAPHY),
        )
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            cacheMode = CacheMode.STALE_IF_ERROR,
        )
        val engine = DefaultEnrichmentEngine(
            registry = ProviderRegistry(listOf(failingProvider)),
            cache = cache,
            config = config,
            synthesizers = listOf(synthesizer),
        )
        val compositeKey = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ARTIST_TIMELINE)

        // When - enriching only the composite type, whose one dependency resolves to a stale substitute
        val results = engine.enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - the caller sees the synthesizer's own NotFound, but it must not reach the negative cache
        assertNotNull(
            "the synthesizer's own NotFound should still surface",
            results.raw[EnrichmentType.ARTIST_TIMELINE] as? EnrichmentResult.NotFound,
        )
        assertNull(cache.negativeStored["$compositeKey:${EnrichmentType.ARTIST_TIMELINE}"])
    }

    @Test
    fun `a composite synthesized NotFound over a filter-emptied dependency is not negative-cached`() = runTest {
        // Given - a dependency the catalog filter applies to, whose live call answers a real
        // Success, an AVAILABLE_ONLY filter that empties every item, and a synthesizer that
        // always answers NotFound
        val cache = FakeEnrichmentCache()
        val provider = FakeProvider(
            id = "live",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentResult.Success(
                    type = EnrichmentType.SIMILAR_ARTISTS,
                    data = EnrichmentData.SimilarArtists(listOf(SimilarArtist(name = "Thom Yorke", matchScore = 0.9f))),
                    provider = "live",
                    confidence = 0.9f,
                ),
            )
        }
        val synthesizer = AlwaysEmptySynthesizer(
            type = EnrichmentType.ARTIST_TIMELINE,
            dependencies = setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            catalogProvider = CatalogProvider { queries -> queries.map { CatalogMatch(available = false, source = "test") } },
            catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
        )
        val engine = DefaultEnrichmentEngine(
            registry = ProviderRegistry(listOf(provider)),
            cache = cache,
            config = config,
            synthesizers = listOf(synthesizer),
        )
        val compositeKey = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ARTIST_TIMELINE)

        // When - enriching only the composite type, whose one dependency filters to empty
        engine.enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - the emptiness under the synthesizer came from the local catalog, so its NotFound
        // must not be written as a negative-cache entry
        assertNull(cache.negativeStored["$compositeKey:${EnrichmentType.ARTIST_TIMELINE}"])
    }
}
