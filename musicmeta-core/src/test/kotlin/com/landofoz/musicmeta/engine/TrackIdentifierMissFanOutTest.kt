package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
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
 * The engine skips the whole provider fan-out when identity resolution comes back with suggestions
 * ([DefaultEnrichmentEngine]), which is right for a *name* that resolves to nothing — the consumer
 * has to say which entity they meant before anything can be fetched. An identifier that resolves to
 * nothing is a different miss: the title and artist on the request are still perfectly good, and
 * providers with no interest in MusicBrainz ids can answer from them. Suggestions raised on the
 * identifier path would spend every one of those answers, so no identifier path raises them.
 *
 * Pinned at the engine, because neither half is wrong on its own: the short-circuit is correct, and
 * so is offering suggestions for a search that found nothing. Only the two together lose a track.
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
    fun `a title that resolves to nothing still short-circuits, suggestions and all`() = runTest {
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

        // Then - the fan-out is skipped and the suggestions reach the consumer instead. Which entity
        // was meant is unanswered here, so fetching anything for it would be a guess.
        val notFound = results.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.NotFound
        assertEquals(listOf("rec-live-1"), notFound.suggestions?.map { it.identifiers.musicBrainzId })
        assertEquals(0, lyrics.enrichCalls.size)
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
