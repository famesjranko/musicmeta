package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recording MBID MusicBrainz does not hold may cost a track request: at most the types the
 * identity provider itself answers, and never a provider that did not need the identifier.
 *
 * Canonical identity resolution and provider eligibility are independent facts
 * ([DefaultEnrichmentEngine]): a dead identifier or an unresolved name both leave the title and
 * artist on the request perfectly good, and a provider with no interest in MusicBrainz ids answers
 * from them regardless of which miss it was. Suggestions, when the identity provider offers any,
 * surface once at the top level alongside whatever the still-eligible providers found.
 */
class TrackIdentifierMissFanOutTest {

    private val httpClient = FakeHttpClient()
    private val musicBrainz = MusicBrainzProvider(httpClient, RateLimiter(0))

    private fun lyricsProvider() = FakeProvider(
        id = "lyrics",
        capabilities = listOf(ProviderCapability(EnrichmentType.LYRICS_PLAIN, 100)),
    ).also {
        it.givenResult(
            EnrichmentType.LYRICS_PLAIN,
            EnrichmentResult.Success(EnrichmentType.LYRICS_PLAIN, EnrichmentData.Lyrics(plainLyrics = "Say your prayers"), "lyrics", 1f),
        )
    }

    private fun engine(lyrics: FakeProvider) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(musicBrainz, lyrics)),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a recording mbid MusicBrainz does not hold still leaves every other provider its turn`() = runTest {
        // Given - a track whose recordings MusicBrainz holds, carrying an identifier it does not,
        // and a second provider that answers from the title and artist alone
        httpClient.givenJsonResponse("recording?query", POOL)
        val lyrics = lyricsProvider()

        // When - a type only that second provider answers is enriched
        val results = engine(lyrics).enrich(
            EnrichmentRequest.forTrack("Enter Sandman", "Metallica", mbid = DEAD_MBID),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - it was asked, and its answer is the consumer's. A dead identifier that skipped the
        // fan-out would cost this request lyrics that never depended on MusicBrainz at all.
        assertEquals(1, lyrics.enrichCalls.count { it.second == EnrichmentType.LYRICS_PLAIN })
        assertTrue(results.raw[EnrichmentType.LYRICS_PLAIN] is EnrichmentResult.Success)
    }

    @Test
    fun `a title that resolves to nothing still fans out, suggestions reach the top level`() = runTest {
        // Given - a track MusicBrainz has no recording for under that name, so only the fuzzy search
        // (the one asking for three) has near-misses for identity resolution to offer
        httpClient.givenJsonResponse(FUZZY_SEARCH, FUZZY_POOL)
        httpClient.givenJsonResponse("recording?query", EMPTY_POOL)
        val lyrics = lyricsProvider()

        // When - the same type is enriched for a request naming no identifier
        val results = engine(lyrics).enrich(
            EnrichmentRequest.forTrack("Entr Sandman", "Metallica"),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - lyrics declares no identifier requirement, so it is independently eligible and
        // runs best-effort; the suggestions reach the consumer once, at the top level, never copied
        // onto the per-type result
        val success = results.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
        assertEquals(1, lyrics.enrichCalls.size)
        assertEquals(listOf("rec-live-1"), results.identity.suggestions.map { it.identifiers.musicBrainzId })
    }

    private companion object {
        const val DEAD_MBID = "rec-unknown"

        /** `searchRecordingsFuzzy` is the only recording search that asks for three. */
        const val FUZZY_SEARCH = "limit=3"

        val POOL = """
            {
              "recordings": [
                {
                  "id": "rec-live-1", "score": 92, "title": "Enter Sandman",
                  "releases": [
                    {
                      "status": "Official",
                      "release-group": {"id": "rg-live", "title": "Live Shit", "primary-type": "Album"}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        const val EMPTY_POOL = """{"recordings": []}"""

        val FUZZY_POOL = """
            {"recordings": [{"id": "rec-live-1", "score": 80, "title": "Enter Sandman"}]}
        """.trimIndent()
    }
}
