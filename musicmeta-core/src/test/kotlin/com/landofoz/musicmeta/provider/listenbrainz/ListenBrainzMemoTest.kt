package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testkit.countMatching
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * [ListenBrainzProvider] holds no [com.landofoz.musicmeta.engine.ProviderCallScope] memo over
 * `popularity/top-recordings-for-artist/{mbid}`. ARTIST_TOP_TRACKS always calls it, and
 * ARTIST_POPULARITY calls it only as a fallback when the batch `popularity/artist` call returns no
 * data for the artist -- the two collide only in that fallback case, which is what the second test
 * here characterises: a batch hit never reaches the fallback, so nothing collides.
 */
class ListenBrainzMemoTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: ListenBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = ListenBrainzProvider(httpClient, RateLimiter(0L))
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    private fun artistRequest() = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = MBID),
        name = "Radiohead",
    )

    @Ignore(
        "Red now: when the batch popularity/artist call returns no data for the artist, " +
            "ARTIST_POPULARITY's fallback and ARTIST_TOP_TRACKS both call " +
            "popularity/top-recordings-for-artist/{mbid}, costing two requests to that endpoint in " +
            "one enrich() call where a memo would cost one. Remove this mark only once that count " +
            "is one; this assertion must go red first if it is not.",
    )
    @Test
    fun `one artist's fanout calls top-recordings once, not once per type, when the batch call is empty`() = runTest {
        // Given - the batch endpoint holds nothing for this artist, so ARTIST_POPULARITY falls
        // back to the same endpoint ARTIST_TOP_TRACKS calls directly
        httpClient.givenJsonResponse("popularity/artist", "[]")
        httpClient.givenJsonResponse("top-recordings-for-artist", TOP_RECORDINGS_JSON)

        // When - both types are enriched in one call
        engine().enrich(artistRequest(), setOf(EnrichmentType.ARTIST_POPULARITY, EnrichmentType.ARTIST_TOP_TRACKS))

        // Then - the fallback and ARTIST_TOP_TRACKS shared one request, not one each
        httpClient.assertNoUrlRequestedTwice()
    }

    @Test
    fun `a batch popularity hit never falls back to top-recordings, so nothing collides`() = runTest {
        // Given - the batch endpoint holds data for this artist, so ARTIST_POPULARITY's fallback
        // never runs
        httpClient.givenJsonResponse(
            "popularity/artist",
            """[{"artist_mbid": "$MBID", "total_listen_count": 500, "total_user_count": 42}]""",
        )
        httpClient.givenJsonResponse("top-recordings-for-artist", TOP_RECORDINGS_JSON)

        // When - both types are enriched in one call
        engine().enrich(artistRequest(), setOf(EnrichmentType.ARTIST_POPULARITY, EnrichmentType.ARTIST_TOP_TRACKS))

        // Then - one batch call answered ARTIST_POPULARITY and one top-recordings call answered
        // ARTIST_TOP_TRACKS, and neither endpoint was hit twice
        httpClient.assertNoUrlRequestedTwice()
        assertEquals(1, httpClient.countMatching("popularity/artist"))
        assertEquals(1, httpClient.countMatching("top-recordings-for-artist"))
    }

    private companion object {
        const val MBID = "a74b1b7f-71a5-4011-9441-d0b5e4122711"

        val TOP_RECORDINGS_JSON = """
            [
              {"recording_mbid": "rec1", "recording_name": "Creep", "artist_name": "Radiohead", "total_listen_count": 1000}
            ]
        """.trimIndent()
    }
}
