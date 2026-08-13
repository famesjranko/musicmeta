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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a MusicBrainz-resolved result carries [LookupProvenance.QUALIFIER_FALLBACK_NAME] or
 * [LookupProvenance.EXACT_NAME] must reflect which search actually won, not a title heuristic.
 */
class MusicBrainzQualifierFallbackProvenanceTest {

    private fun engine(httpClient: FakeHttpClient) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(MusicBrainzProvider(httpClient, RateLimiter(0)))),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a decorated title resolved via the stripped fallback candidate carries QUALIFIER_FALLBACK_NAME`() = runTest {
        // Given - the literal decorated-title search comes up empty, and the stripped "Enter
        // Sandman" fallback candidate resolves to an authoritative match
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse(
            "recording%3A%22Enter+Sandman%22",
            """{"recordings": [{"id": "rec-studio", "score": 100, "title": "Enter Sandman", "length": 331000,
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}]}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman (Remastered 2021)", "Metallica")

        // When - enriching for TRACK_METADATA
        val results = engine(httpClient).enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - the result carries the truthful fallback provenance, not a guess from title shape
        val success = results.raw[EnrichmentType.TRACK_METADATA] as EnrichmentResult.Success
        assertEquals("rec-studio", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(LookupProvenance.QUALIFIER_FALLBACK_NAME, success.provenance)
    }

    @Test
    fun `the same decorated title resolved on the literal search carries EXACT_NAME`() = runTest {
        // Given - the literal decorated title IS the recording's own MusicBrainz title, so the
        // direct search resolves it without ever trying a stripped candidate
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse(
            "recording%3A%22Enter+Sandman+%5C%28Remastered+2021%5C%29%22",
            """{"recordings": [{"id": "rec-literal", "score": 100, "title": "Enter Sandman (Remastered 2021)",
                "length": 331000, "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}]}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman (Remastered 2021)", "Metallica")

        // When - enriching for TRACK_METADATA
        val results = engine(httpClient).enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - the result carries EXACT_NAME, and no fallback search ever ran
        val success = results.raw[EnrichmentType.TRACK_METADATA] as EnrichmentResult.Success
        assertEquals("rec-literal", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
        assertTrue(httpClient.requestedUrls.none { it.contains("recording%3A%22Enter+Sandman%22") })
    }
}
