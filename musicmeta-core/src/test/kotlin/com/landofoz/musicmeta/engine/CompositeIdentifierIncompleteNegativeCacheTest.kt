package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A synthesizer for [FakeCompositeType] can only ever answer as truthfully as the sub-chains
 * [resolveTypes] fed it. Unlike a chain-backed type, a composite earns no [ChainExecution] of its
 * own, so its `identifierIncomplete` fact has to be derived from its dependencies' — see
 * `docs/pitfalls.md`, "a provider that was never asked cannot speak for the chain".
 */
private val FakeCompositeType = EnrichmentType.ARTIST_TIMELINE

/** Reports NotFound unless at least one dependency answered — never Success-with-nothing. */
private object NotFoundOnEmptySynthesizer : CompositeSynthesizer {
    override val type: EnrichmentType = FakeCompositeType
    override val dependencies: Set<EnrichmentType> =
        setOf(EnrichmentType.ARTIST_DISCOGRAPHY, EnrichmentType.BAND_MEMBERS)

    override fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult {
        val answered = dependencies.any { resolved[it] is EnrichmentResult.Success }
        return if (answered) {
            EnrichmentResult.Success(type, EnrichmentData.ArtistTimeline(emptyList()), "synth", 1f)
        } else {
            EnrichmentResult.NotFound(type, "synth")
        }
    }
}

class CompositeIdentifierIncompleteNegativeCacheTest {

    private val request = EnrichmentRequest.forArtist("Radiohead")

    @Test
    fun `a composite NotFound is not negative-cached when a dependency's sub-chain was skipped for a missing identifier`() = runTest {
        // Given - discography's chain runs to exhaustion and finds nothing, but band members'
        // only provider requires an MBID this request never had, so it is skipped, not asked
        val cache = InMemoryEnrichmentCache()
        val discography = FakeProvider(
            id = "discography", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
        ).also { it.givenResult(EnrichmentType.ARTIST_DISCOGRAPHY, EnrichmentResult.NotFound(EnrichmentType.ARTIST_DISCOGRAPHY, "discography")) }
        val bandMembers = FakeProvider(
            id = "band-members",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.BAND_MEMBERS, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
            ),
        )
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(discography, bandMembers)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
            synthesizers = listOf(NotFoundOnEmptySynthesizer),
        )

        // When - enriching the composite type
        engine.enrich(request, setOf(FakeCompositeType))

        // Then - the synthesizer's NotFound reached the caller, but writeBack must not treat a
        // chain that was never asked as proof the data does not exist
        assertNull(cache.getNegative(DefaultEnrichmentEngine.entityKeyFor(request, FakeCompositeType), FakeCompositeType))
    }

    @Test
    fun `a composite NotFound is negative-cached when every dependency's sub-chain ran to exhaustion`() = runTest {
        // Given - both dependencies' providers declare no identifier requirement and both run,
        // finding nothing — the control case with no skip at all
        val cache = InMemoryEnrichmentCache()
        val discography = FakeProvider(
            id = "discography", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
        ).also { it.givenResult(EnrichmentType.ARTIST_DISCOGRAPHY, EnrichmentResult.NotFound(EnrichmentType.ARTIST_DISCOGRAPHY, "discography")) }
        val bandMembers = FakeProvider(
            id = "band-members", capabilities = listOf(ProviderCapability(EnrichmentType.BAND_MEMBERS, 100)),
        ).also { it.givenResult(EnrichmentType.BAND_MEMBERS, EnrichmentResult.NotFound(EnrichmentType.BAND_MEMBERS, "band-members")) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(discography, bandMembers)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
            synthesizers = listOf(NotFoundOnEmptySynthesizer),
        )

        // When - enriching the composite type
        engine.enrich(request, setOf(FakeCompositeType))

        // Then - nobody was skipped, so the synthesizer's NotFound is genuinely negative-cacheable
        assertNotNull(cache.getNegative(DefaultEnrichmentEngine.entityKeyFor(request, FakeCompositeType), FakeCompositeType))
    }
}
