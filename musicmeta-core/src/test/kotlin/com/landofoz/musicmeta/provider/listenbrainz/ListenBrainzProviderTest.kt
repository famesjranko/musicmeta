package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
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

class ListenBrainzProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: ListenBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = ListenBrainzProvider(httpClient, RateLimiter(0L))
    }

    @Test
    fun `enrich returns popularity data with top tracks`() = runTest {
        // Given
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "listenbrainz.org",
            """[
                {
                    "recording_mbid": "abc",
                    "track_name": "Creep",
                    "artist_name": "Radiohead",
                    "total_listen_count": 50000
                },
                {
                    "recording_mbid": "def",
                    "track_name": "Karma Police",
                    "artist_name": "Radiohead",
                    "total_listen_count": 45000
                }
            ]""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("listenbrainz", success.provider)
        assertEquals(0.95f, success.confidence, 0.01f)
        val popularity = success.data as EnrichmentData.Popularity
        assertEquals(2, popularity.topTracks!!.size)
        assertEquals("Creep", popularity.topTracks!![0].title)
        assertEquals(50000L, popularity.topTracks!![0].listenCount)
        assertEquals("abc", popularity.topTracks!![0].identifiers.musicBrainzId)
        assertEquals(1, popularity.topTracks!![0].rank)
        assertEquals("Karma Police", popularity.topTracks!![1].title)
        assertEquals(45000L, popularity.topTracks!![1].listenCount)
        assertEquals(2, popularity.topTracks!![1].rank)
    }

    @Test
    fun `enrich returns NotFound when no artist MBID in identifiers`() = runTest {
        // Given
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("listenbrainz", notFound.provider)
    }

    @Test
    fun `enrich returns NotFound when API returns empty results`() = runTest {
        // Given
        val artistMbid = "00000000-0000-0000-0000-000000000000"
        httpClient.givenJsonResponse("listenbrainz.org", "[]")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Unknown Artist",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("listenbrainz", notFound.provider)
    }

    @Test
    fun `enrich handles API returning null response`() = runTest {
        // Given - no response configured, so fetchJsonArray returns null
        val artistMbid = "11111111-1111-1111-1111-111111111111"

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Offline Artist",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich skips objects with missing recording_mbid`() = runTest {
        // Given — API returns array where some objects lack recording_mbid
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "listenbrainz.org",
            """[
                {
                    "track_name": "No MBID Track",
                    "artist_name": "Radiohead",
                    "total_listen_count": 10000
                },
                {
                    "recording_mbid": "",
                    "track_name": "Blank MBID Track",
                    "artist_name": "Radiohead",
                    "total_listen_count": 8000
                },
                {
                    "recording_mbid": "valid-mbid-123",
                    "track_name": "Valid Track",
                    "artist_name": "Radiohead",
                    "total_listen_count": 5000
                }
            ]""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When — enriching for popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then — only the track with a valid recording_mbid is included
        assertTrue(result is EnrichmentResult.Success)
        val popularity = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(1, popularity.topTracks!!.size)
        assertEquals("Valid Track", popularity.topTracks!![0].title)
        assertEquals("valid-mbid-123", popularity.topTracks!![0].identifiers.musicBrainzId)
    }

    @Test
    fun `enrich returns track popularity via batch recording endpoint`() = runTest {
        // Given -- batch recording popularity response
        val recordingMbid = "rec-mbid-123"
        httpClient.givenJsonResponse(
            "popularity/recording",
            """[
                {
                    "recording_mbid": "$recordingMbid",
                    "total_listen_count": 99000,
                    "total_user_count": 8500
                }
            ]""",
        )
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = recordingMbid),
            title = "Karma Police",
            artist = "Radiohead",
        )

        // When -- enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then -- Success with track-level listen count and user count
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(99000L, data.listenCount)
        assertEquals(8500L, data.listenerCount)
    }

    @Test
    fun `enrich returns NotFound for TRACK_POPULARITY when no recording data`() = runTest {
        // Given -- empty batch recording response
        httpClient.givenJsonResponse("popularity/recording", "[]")
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "no-data-mbid"),
            title = "Unknown",
            artist = "Nobody",
        )

        // When -- enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then -- NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns artist popularity via batch artist endpoint`() = runTest {
        // Given -- batch artist popularity response
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "popularity/artist",
            """[
                {
                    "artist_mbid": "$artistMbid",
                    "total_listen_count": 500000,
                    "total_user_count": 42000
                }
            ]""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When -- enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then -- Success with batch artist popularity data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(500000L, data.listenCount)
        assertEquals(42000L, data.listenerCount)
    }

    @Test
    fun `enrich falls back to top-recordings when batch artist returns empty`() = runTest {
        // Given -- empty batch artist response but valid top-recordings
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse("popularity/artist", "[]")
        httpClient.givenJsonResponse(
            "top-recordings-for-artist",
            """[
                {
                    "recording_mbid": "abc",
                    "track_name": "Creep",
                    "artist_name": "Radiohead",
                    "total_listen_count": 50000
                }
            ]""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When -- enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then -- Success with fallback top-recordings data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(1, data.topTracks!!.size)
        assertEquals("Creep", data.topTracks!![0].title)
    }

    @Test
    fun `enrich returns Success with zero listen count`() = runTest {
        // Given — API returns a track with total_listen_count of zero
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "listenbrainz.org",
            """[
                {
                    "recording_mbid": "zero-plays",
                    "track_name": "Unpopular Track",
                    "artist_name": "Radiohead",
                    "total_listen_count": 0
                }
            ]""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When — enriching for popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then — Success with zero listen count (not filtered out)
        assertTrue(result is EnrichmentResult.Success)
        val popularity = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(1, popularity.topTracks!!.size)
        assertEquals(0L, popularity.topTracks!![0].listenCount)
    }
}
