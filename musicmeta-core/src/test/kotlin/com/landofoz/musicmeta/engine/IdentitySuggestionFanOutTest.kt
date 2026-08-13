package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A canonical identity `NotFound` carrying suggestions is not a global admission decision: it must
 * change nothing about which providers run or what they answer, only the top-level identity
 * metadata. The primary test below is metamorphic — the same registry and request, run once with
 * `suggestions = null` and once with a non-empty list, must reach the same provider invocations and
 * per-type results.
 */
class IdentitySuggestionFanOutTest {

    private val suggestions = listOf(
        SearchCandidate(
            "Bush", null, "1992", "GB", "Group", 75, null,
            EnrichmentIdentifiers(musicBrainzId = "mbid-gb"), "mb", disambiguation = "British rock band",
        ),
    )

    private fun idProvider(withSuggestions: Boolean) = FakeProvider(
        id = "mb", isIdentityProvider = true,
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
    ).also {
        it.givenIdentityResult(
            EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = if (withSuggestions) suggestions else null),
        )
    }

    private fun noneProvider(id: String, type: EnrichmentType, result: EnrichmentResult) =
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100))).also { it.givenResult(type, result) }

    private fun idOnlyProvider(id: String, type: EnrichmentType, requirement: IdentifierRequirement, result: EnrichmentResult) =
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100, identifierRequirement = requirement)))
            .also { it.givenResult(type, result) }

    private fun engine(vararg providers: FakeProvider) = DefaultEnrichmentEngine(
        ProviderRegistry(providers.toList()),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = true),
    )

    // --- Metamorphic: suggestions=null vs suggestions=non-empty must not change eligibility ---

    private suspend fun assertInvocationParity(
        buildProviders: () -> List<FakeProvider>,
        types: Set<EnrichmentType>,
        request: EnrichmentRequest,
    ) {
        val bestEffortProviders = buildProviders()
        val bestEffortEngine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider(withSuggestions = false)) + bestEffortProviders),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = true),
        )
        val bestEffort = bestEffortEngine.enrich(request, types)

        val suggestionProviders = buildProviders()
        val suggestionEngine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider(withSuggestions = true)) + suggestionProviders),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = true),
        )
        val withSuggestions = suggestionEngine.enrich(request, types)

        // Same invocation counts on both sides, provider-by-provider
        for ((noSuggestions, withSuggest) in bestEffortProviders.zip(suggestionProviders)) {
            assertEquals(
                "provider ${noSuggestions.id} invocation count should match",
                noSuggestions.enrichCalls.size, withSuggest.enrichCalls.size,
            )
        }
        // Same per-type result shape (Success/NotFound), and success payload equality
        for (type in types) {
            val a = bestEffort.raw[type]
            val b = withSuggestions.raw[type]
            assertEquals("per-type result kind should match for $type", a?.javaClass, b?.javaClass)
            if (a is EnrichmentResult.Success && b is EnrichmentResult.Success) {
                assertEquals(a.data, b.data)
                assertEquals(a.provider, b.provider)
            }
        }
        // Only the top-level canonical metadata differs
        assertEquals(IdentityMatch.BEST_EFFORT, bestEffort.identity?.match)
        assertEquals(IdentityMatch.SUGGESTIONS, withSuggestions.identity?.match)
        assertEquals(emptyList<SearchCandidate>(), bestEffort.identity?.suggestions)
        assertEquals(suggestions, withSuggestions.identity?.suggestions)
    }

    @Test fun `NONE-only chain has identical invocation and outcome parity`() = runTest {
        // Given - a single provider whose capability names no identifier requirement
        // When - the same request runs once with no suggestions and once with suggestions
        // Then - assertInvocationParity holds: same invocations, same per-type result, top-level only differs
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        assertInvocationParity(
            buildProviders = {
                listOf(noneProvider("art", EnrichmentType.ALBUM_ART, art("art")))
            },
            types = setOf(EnrichmentType.ALBUM_ART),
            request = req,
        )
    }

    @Test fun `MBID-required-only chain has identical invocation and outcome parity, empty identifiers`() = runTest {
        // Given - a single MBID-only provider and a request supplying no identifiers
        // When - the same request runs once with no suggestions and once with suggestions
        // Then - assertInvocationParity holds: the provider stays uncalled on both sides
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        assertInvocationParity(
            buildProviders = {
                listOf(
                    idOnlyProvider(
                        "caa", EnrichmentType.ALBUM_ART, IdentifierRequirement.MUSICBRAINZ_ID,
                        art("caa"),
                    ),
                )
            },
            types = setOf(EnrichmentType.ALBUM_ART),
            request = req,
        )
    }

    @Test fun `identifier-required-only chain has identical invocation and outcome parity, matching identifier supplied`() = runTest {
        // Given - a WIKIDATA_ID-only provider and a request supplying a matching wikidataId. A
        // supplied musicBrainzId alone would skip identity resolution entirely
        // (needsIdentityResolution), which is a different scenario; wikidataId leaves resolution
        // mandatory (no MBID/release-group) while still satisfying this chain's own requirement.
        // When - the same request runs once with no suggestions and once with suggestions
        // Then - assertInvocationParity holds: the provider runs on both sides
        val req = EnrichmentRequest.forAlbum("Album", "Bush", identifiers = EnrichmentIdentifiers(wikidataId = "Q1"))
        assertInvocationParity(
            buildProviders = {
                listOf(
                    idOnlyProvider(
                        "wikidata", EnrichmentType.ALBUM_ART, IdentifierRequirement.WIKIDATA_ID,
                        art("wikidata"),
                    ),
                )
            },
            types = setOf(EnrichmentType.ALBUM_ART),
            request = req,
        )
    }

    @Test fun `mixed NONE plus identifier-required chain has identical invocation and outcome parity`() = runTest {
        // Given - an MBID-only provider the request cannot satisfy, and a NONE provider
        // When - the same request runs once with no suggestions and once with suggestions
        // Then - assertInvocationParity holds for both providers independently
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        assertInvocationParity(
            buildProviders = {
                listOf(
                    idOnlyProvider(
                        "caa", EnrichmentType.ALBUM_ART, IdentifierRequirement.MUSICBRAINZ_ID,
                        art("caa"),
                    ),
                    noneProvider("deezer", EnrichmentType.ALBUM_ART, art("deezer")),
                )
            },
            types = setOf(EnrichmentType.ALBUM_ART),
            request = req,
        )
    }

    @Test fun `mergeable type has identical invocation and outcome parity`() = runTest {
        // Given - a GENRE provider feeding the collect-all mergeable path
        // When - the same request runs once with no suggestions and once with suggestions
        // Then - assertInvocationParity holds for the merged result too
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        assertInvocationParity(
            buildProviders = {
                listOf(
                    noneProvider(
                        "p1", EnrichmentType.GENRE,
                        EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "p1", 0.9f),
                    ),
                )
            },
            types = setOf(EnrichmentType.GENRE),
            request = req,
        )
    }

    @Test fun `unrelated supplied identifier has identical invocation and outcome parity`() = runTest {
        // Given - an MBID-only provider and a request supplying only an unrelated wikidataId
        // When - the same request runs once with no suggestions and once with suggestions
        // Then - assertInvocationParity holds: the provider stays uncalled on both sides
        val req = EnrichmentRequest.forAlbum("Album", "Bush", identifiers = EnrichmentIdentifiers(wikidataId = "Q999"))
        assertInvocationParity(
            buildProviders = {
                listOf(
                    idOnlyProvider(
                        "caa", EnrichmentType.ALBUM_ART, IdentifierRequirement.MUSICBRAINZ_ID,
                        art("caa"),
                    ),
                )
            },
            types = setOf(EnrichmentType.ALBUM_ART),
            request = req,
        )
    }

    // --- Focused ---

    @Test fun `suggestions plus a NONE provider succeeds best-effort, suggestions kept at the top level`() = runTest {
        // Given - identity fails with suggestions and a downstream NONE-requirement provider
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        val art = noneProvider("deezer", EnrichmentType.ALBUM_ART, art("deezer"))
        val e = engine(idProvider(withSuggestions = true), art)

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the provider ran, its Success is best-effort, and suggestions survive at the top
        assertEquals(1, art.enrichCalls.size)
        val success = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(IdentityMatch.BEST_EFFORT, success.identityMatch)
        assertEquals(IdentityMatch.SUGGESTIONS, results.identity?.match)
        assertEquals(suggestions, results.identity?.suggestions)
    }

    @Test fun `suggestions plus an ID-only provider with no identifier is not called`() = runTest {
        // Given - identity fails with suggestions and an MBID-only provider the request cannot satisfy
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        val caa = idOnlyProvider("caa", EnrichmentType.ALBUM_ART, IdentifierRequirement.MUSICBRAINZ_ID, art("caa"))
        val e = engine(idProvider(withSuggestions = true), caa)

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the ineligible provider is never called, and the type is an honest NotFound
        assertEquals(0, caa.enrichCalls.size)
        assertEquals(EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers"), results.raw[EnrichmentType.ALBUM_ART])
    }

    @Test fun `suggestions plus an ID-only provider with the identifier supplied is called`() = runTest {
        // Given - identity fails with suggestions and a WIKIDATA_ID-only provider whose identifier
        // the request supplies. A supplied musicBrainzId alone would skip identity resolution (no
        // suggestions to offer); wikidataId keeps resolution mandatory while satisfying this
        // provider's own requirement.
        val req = EnrichmentRequest.forAlbum("Album", "Bush", identifiers = EnrichmentIdentifiers(wikidataId = "Q1"))
        val caa = idOnlyProvider("caa", EnrichmentType.ALBUM_ART, IdentifierRequirement.WIKIDATA_ID, art("caa"))
        val e = engine(idProvider(withSuggestions = true), caa)

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the provider ran and its Success is best-effort
        assertEquals(1, caa.enrichCalls.size)
        val success = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(IdentityMatch.BEST_EFFORT, success.identityMatch)
    }

    @Test fun `suggestions never copy onto a per-type result`() = runTest {
        // Given - identity fails with suggestions and the requested type's own provider finds nothing
        val req = EnrichmentRequest.forAlbum("Album", "Bush")
        val deezer = noneProvider("deezer", EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "deezer"))
        val e = engine(idProvider(withSuggestions = true), deezer)

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the per-type NotFound carries no suggestions; only the top level does
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.NotFound
        assertNull(result.suggestions)
        assertEquals(suggestions, results.identity?.suggestions)
    }

    private fun art(p: String) = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x.com/$p.jpg"), p, 0.95f)
}
