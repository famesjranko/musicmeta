package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A type identity resolution answers directly from its own payload ([EnrichmentType.GENRE] and its
 * [IDENTITY_TYPES] siblings) skips the provider chain entirely, so it earns no [ChainExecution] for
 * [stampProvenance] to read [ChainExecution.winningRequirement] from. Its provenance must still be
 * the route identity resolution actually observed — never a blanket `EXACT_NAME`/`FUZZY_NAME` guess
 * from [CanonicalStatus] alone.
 */
class IdentityFastPathProvenanceTest {

    private fun engine(httpClient: FakeHttpClient) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(MusicBrainzProvider(httpClient, RateLimiter(0)))),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `an MBID-driven identity fast path reports CANONICAL_ID, not a name-route guess`() = runTest {
        // Given - a request naming its own release MBID; MusicBrainz answers GENRE with a direct
        // id lookup, never a name search
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse(
            "release/thin1",
            """{"id": "thin1", "title": "OK Computer", "date": "1997-06-16", "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {"id": "group123", "primary-type": "Album",
                    "tags": [{"name": "alternative rock", "count": 5}]}}""",
        )
        val request = EnrichmentRequest.forAlbumByMbid("thin1")

        // When - enriching for GENRE, which the identity fast path answers directly
        val results = engine(httpClient).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the fast-pathed result carries the id lookup's real route, CANONICAL_ID
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, success.provenance)
    }

    @Test
    fun `a name-resolved identity fast path still reports EXACT_NAME`() = runTest {
        // Given - a request naming no MBID; MusicBrainz resolves it by an exact name search
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse(
            "release?query",
            """{"releases": [{"id": "thin1", "score": 100, "title": "OK Computer",
                "date": "1997-06-16", "country": "GB", "artist-credit": [{"artist": {"name": "Radiohead"}}],
                "release-group": {"id": "group123", "primary-type": "Album",
                    "tags": [{"name": "alternative rock", "count": 5}]}}]}""",
        )
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for GENRE, which the identity fast path answers directly
        val results = engine(httpClient).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the fast-pathed result carries the name search's real route, EXACT_NAME
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
    }

    @Test
    fun `a qualifier-fallback identity fast path keeps its own self-reported provenance`() = runTest {
        // Given - the literal decorated-title search is left unstubbed (comes up empty), and the
        // stripped "OK Computer" fallback candidate resolves to an authoritative match
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse(
            "release%3A%22OK+Computer%22",
            """{"releases": [{"id": "thin1", "score": 100, "title": "OK Computer",
                "date": "1997-06-16", "country": "GB", "artist-credit": [{"artist": {"name": "Radiohead"}}],
                "release-group": {"id": "group123", "primary-type": "Album",
                    "tags": [{"name": "alternative rock", "count": 5}]}}]}""",
        )
        val request = EnrichmentRequest.forAlbum("OK Computer (Remastered)", "Radiohead")

        // When - enriching for GENRE, which the identity fast path answers directly
        val results = engine(httpClient).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the fast-pathed result preserves the search's own self-reported provenance
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.QUALIFIER_FALLBACK_NAME, success.provenance)
    }
}
