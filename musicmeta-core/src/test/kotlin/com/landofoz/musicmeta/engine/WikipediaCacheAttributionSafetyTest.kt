package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LicenseRelation
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaCacheAttributionSafetyTest {
    private val type = EnrichmentType.ALBUM_ART
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `unsafe Wikipedia primaries promote a complete other-provider alternative on every cache route`() = runTest {
        // Given - cache routes holding complete but non-reusable Wikipedia file claims
        val unsafe = listOf(
            attribution(nonFree = true),
            attribution(nonFree = null),
            attribution(restrictions = listOf("no commercial use")),
            attribution(restrictions = null),
            attribution(licenses = emptyList()),
            attribution(licenses = listOf(ccBy, ccBySa), relation = LicenseRelation.UNKNOWN),
            attribution(licenses = listOf(ContentLicense("All rights reserved"))),
            attribution(licenses = listOf(ContentLicense("CC BY 4.0", "https://example.test/not-by"))),
            attribution(copyrighted = false),
            attribution(copyrighted = null),
            attribution(usageTerms = "CC BY-NC 4.0"),
            attribution(sourceUrl = "http://commons.wikimedia.org/wiki/File:unsafe.jpg"),
            attribution(creator = null, credit = null, attributionText = null),
        )
        for ((route, claim) in listOf("full", "partial", "stale", "pinned", "fresh").flatMap { route -> unsafe.map { route to it } }) {
            val cache = IndependentCache()
            val key = entityKeyFor(request, type)
            cache.put(key, type, wikipediaArtwork(claim, alternatives = listOf(safeAlternative)), CanonicalStatus.RESOLVED, 1)
            if (route == "stale" || route == "pinned") cache.now = 2
            if (route == "pinned") cache.markManuallySelected(key, type)
            val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(type, 100)))
                .also { it.givenResult(type, EnrichmentResult.Error(type, "fresh", "offline")) }
            val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR))

            // When - the engine serves the cached primary through each cache route
            val types = if (route == "partial") setOf(type, EnrichmentType.LABEL) else setOf(type)
            val served = engine.enrichProgressive(request, types).toList().last().raw.getValue(type) as EnrichmentResult.Success

            // Then - the safe complete tuple is promoted and a selected entry remains selected
            assertEquals("other", served.provider)
            val artwork = served.data as EnrichmentData.Artwork
            assertEquals(safeAlternative.url, artwork.url)
            assertEquals(safeAlternative.attribution, artwork.attribution)
            assertEquals(route == "pinned", cache.isManuallySelected(key, type))
            engine.close()
        }
    }

    @Test fun `unsafe Wikipedia alternatives are removed while reusable and public-domain controls remain`() = runTest {
        // Given - a safe primary with unsafe Wikipedia alternatives and two valid controls
        val unsafeAlternatives = listOf(
            ArtworkSource("wikipedia", "https://images.test/non-free.jpg", attribution = attribution(nonFree = true)),
            ArtworkSource("wikipedia", "https://images.test/restricted.jpg", attribution = attribution(restrictions = listOf("restricted"))),
            ArtworkSource("wikipedia", "https://images.test/ambiguous.jpg", attribution = attribution(licenses = listOf(ccBy, ccBySa), relation = LicenseRelation.UNKNOWN)),
        )
        for (alternative in unsafeAlternatives) {
            val cache = IndependentCache()
            cache.put(entityKeyFor(request, type), type, otherArtwork(alternatives = listOf(alternative)), CanonicalStatus.RESOLVED)
            val engine = engine(cache, FakeProvider("unused", capabilities = listOf(ProviderCapability(type, 100))))

            // When - a fresh cache hit contains a Wikipedia alternative
            val served = engine.enrich(request, setOf(type)).raw.getValue(type) as EnrichmentResult.Success

            // Then - the unsafe alternative is absent from the served result
            assertNull((served.data as EnrichmentData.Artwork).alternatives)
            engine.close()
        }
        for (control in listOf(attribution(), attribution(licenses = listOf(publicDomain), copyrighted = false, attributionRequired = false, usageTerms = null))) {
            val cache = IndependentCache()
            cache.put(entityKeyFor(request, type), type, wikipediaArtwork(control), CanonicalStatus.RESOLVED)

            // When - a cache entry has an explicitly reusable file attribution
            val served = engine(cache, FakeProvider("unused", capabilities = listOf(ProviderCapability(type, 100)))).enrich(request, setOf(type)).raw.getValue(type)

            // Then - CC BY and public-domain files remain available
            assertTrue(served is EnrichmentResult.Success)
        }
    }

    @Test fun `unsafe Wikipedia provider results are not written to a custom cache`() = runTest {
        // Given - a provider answer with a contradictory reusable-looking file claim
        val cache = IndependentCache()
        val provider = FakeProvider("wikipedia", capabilities = listOf(ProviderCapability(type, 100)))
            .also { it.givenResult(type, wikipediaArtwork(attribution(nonFree = true))) }
        val engine = engine(cache, provider)

        // When - the engine fetches and finalizes that live answer
        val served = engine.enrich(request, setOf(type)).raw.getValue(type)

        // Then - it is withheld and no unsafe positive value is persisted
        assertTrue(served is EnrichmentResult.NotFound)
        assertEquals(1, provider.enrichCalls.size)
        assertNull(cache.getIncludingExpired(entityKeyFor(request, type), type))
        engine.close()
    }

    private fun engine(cache: IndependentCache, provider: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

    private fun wikipediaArtwork(attribution: ContentAttribution, alternatives: List<ArtworkSource>? = null) =
        EnrichmentResult.Success(type, EnrichmentData.Artwork("https://images.test/wikipedia.jpg", alternatives = alternatives, attribution = attribution), "wikipedia", 0.9f)

    private fun otherArtwork(alternatives: List<ArtworkSource>? = null) =
        EnrichmentResult.Success(type, EnrichmentData.Artwork("https://images.test/other.jpg", alternatives = alternatives), "other", 0.9f)

    private fun attribution(
        sourceUrl: String = "https://commons.wikimedia.org/wiki/File:safe.jpg",
        creator: String? = "Photographer",
        credit: String? = "Photographer",
        attributionText: String? = null,
        licenses: List<ContentLicense> = listOf(ccBy),
        relation: LicenseRelation = LicenseRelation.ANY_OF,
        copyrighted: Boolean? = true,
        attributionRequired: Boolean? = true,
        nonFree: Boolean? = false,
        usageTerms: String? = "CC BY 4.0",
        restrictions: List<String>? = emptyList(),
    ) = ContentAttribution("File:safe.jpg", sourceUrl, creator, credit, attributionText, licenses, relation, copyrighted, attributionRequired, nonFree, usageTerms, restrictions)

    private companion object {
        val ccBy = ContentLicense("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
        val ccBySa = ContentLicense("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/")
        val publicDomain = ContentLicense("Public domain")
        val safeAlternative = ArtworkSource("other", "https://images.test/other.jpg", attribution = ContentAttribution("File:other.jpg", "https://images.test/other.jpg"))
    }
}
