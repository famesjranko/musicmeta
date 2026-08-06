package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression for a live-testing finding on Radiohead "Karma Police" / album "OK Computer": the
 * album-hinted recording search's 4-candidate pool (trimmed from the real `/recording?query=...`
 * response, 2026-08-06) has exactly one exact-title, score>=80 hit — a `"video": true` music-video
 * recording whose *first* embedded Official/Album release is a compilation ("The Best Of") even
 * though the same recording also carries the actual "OK Computer" release further down the array.
 * The true studio recording (blank disambiguation, non-video) is in the same pool but scores only
 * 77 — MB's own relevance score, independent of correctness — so it was filtered out before
 * ranking ever compared it against the video take.
 */
class MusicBrainzKarmaPoliceRegressionTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `TRACK_METADATA resolves to the studio recording and the requested album, not the music video`() = runTest {
        // Given
        httpClient.givenJsonResponse("recording?query", KARMA_POLICE_ALBUM_HINTED_POOL)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")

        // When
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then — the blank-disambiguation, non-video studio recording wins despite scoring lower
        // than the video take, and the album title is the requested "OK Computer", not the
        // compilation ("The Best Of") that happens to sort first in the winning recording's own
        // releases array
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("studio-recording-id", success.resolvedIdentifiers?.musicBrainzId)
        val data = success.data as EnrichmentData.TrackMetadata
        assertEquals("OK Computer", data.albumTitle)
    }

    @Test
    fun `GENRE identity resolution also picks the studio recording over the music video`() = runTest {
        // Given — same pool, a type that doesn't touch TrackMetadata at all
        httpClient.givenJsonResponse("recording?query", KARMA_POLICE_ALBUM_HINTED_POOL)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("studio-recording-id", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `without an album hint the video take still loses to a same-scoring non-video candidate`() = runTest {
        // Given — two score-100 exact-title candidates, one video, one not, no album on the request
        httpClient.givenJsonResponse(
            "recording?query",
            """
            {
              "recordings": [
                {
                  "id": "video-id", "score": 100, "title": "Karma Police",
                  "disambiguation": "music video", "video": true
                },
                {
                  "id": "clean-id", "score": 100, "title": "Karma Police"
                }
              ]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — the non-video candidate wins even though pool order favours the video one
        assertTrue(result is EnrichmentResult.Success)
        assertEquals("clean-id", (result as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzId)
    }

    private companion object {
        // Trimmed from the real 2026-08-06 musicbrainz.org response for
        // recording:"Karma Police" AND artistname:"Radiohead" AND release:"OK Computer"
        val KARMA_POLICE_ALBUM_HINTED_POOL = """
            {
              "recordings": [
                {
                  "id": "video-recording-id",
                  "score": 100,
                  "title": "Karma Police",
                  "length": 263000,
                  "disambiguation": "music video",
                  "video": true,
                  "releases": [
                    {"status": "Official", "release-group": {"id": "rg-best-of", "primary-type": "Album", "title": "The Best Of"}},
                    {"status": "Official", "release-group": {"id": "rg-jonathan-glazer", "primary-type": "Other", "title": "The Work of Director Jonathan Glazer"}},
                    {"status": "Official", "release-group": {"id": "rg-ok-computer", "primary-type": "Album", "title": "OK Computer"}}
                  ]
                },
                {
                  "id": "telephone-recording-id",
                  "score": 88,
                  "title": "Karma Police voice through telephone",
                  "releases": [
                    {"status": "Bootleg", "release-group": {"id": "rg-oknotok", "primary-type": "Album", "title": "OKNOTOK: White Cassette Tape"}},
                    {"status": "Official", "release-group": {"id": "rg-ok-computer", "primary-type": "Album", "title": "OK Computer"}}
                  ]
                },
                {
                  "id": "space-echo-recording-id",
                  "score": 88,
                  "title": "Karma Police in Space Echo",
                  "releases": [
                    {"status": "Bootleg", "release-group": {"id": "rg-oknotok", "primary-type": "Album", "title": "OKNOTOK: White Cassette Tape"}},
                    {"status": "Official", "release-group": {"id": "rg-ok-computer", "primary-type": "Album", "title": "OK Computer"}}
                  ]
                },
                {
                  "id": "studio-recording-id",
                  "score": 77,
                  "title": "Karma Police",
                  "length": 262426,
                  "releases": [
                    {"status": "Promotion", "release-group": {"id": "rg-promo-only", "primary-type": "Album", "title": "Promo Only: Modern Rock Radio, November 1997"}},
                    {"status": "Official", "release-group": {"id": "rg-festival-anthems", "primary-type": "Album", "title": "Festival Anthems: The Headliners"}},
                    {"status": "Official", "release-group": {"id": "rg-best-of", "primary-type": "Album", "title": "The Best Of"}},
                    {"status": "Official", "release-group": {"id": "rg-ok-computer", "primary-type": "Album", "title": "OK Computer"}}
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
