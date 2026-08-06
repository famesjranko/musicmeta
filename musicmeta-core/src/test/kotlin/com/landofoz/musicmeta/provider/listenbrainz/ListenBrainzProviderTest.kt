package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    "recording_name": "Creep",
                    "artist_name": "Radiohead",
                    "total_listen_count": 50000
                },
                {
                    "recording_mbid": "def",
                    "recording_name": "Karma Police",
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
        // Given - no response configured, so fetchJsonArrayResult is an unstubbed 404
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
                    "recording_name": "No MBID Track",
                    "artist_name": "Radiohead",
                    "total_listen_count": 10000
                },
                {
                    "recording_mbid": "",
                    "recording_name": "Blank MBID Track",
                    "artist_name": "Radiohead",
                    "total_listen_count": 8000
                },
                {
                    "recording_mbid": "valid-mbid-123",
                    "recording_name": "Valid Track",
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
    fun `enrich returns NotFound for TRACK_POPULARITY when recording counts are JSON-null`() = runTest {
        // Given -- LB has no data for the recording: total_listen_count is JSON null, not 0
        httpClient.givenJsonResponse(
            "popularity/recording",
            """[
                {
                    "recording_mbid": "no-data-mbid",
                    "total_listen_count": null,
                    "total_user_count": null
                }
            ]""",
        )
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "no-data-mbid"),
            title = "Unknown",
            artist = "Nobody",
        )

        // When -- enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then -- the null entry is dropped, leaving an empty list -> NotFound, not Success(0, 0)
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich keeps the real entry from a mixed batch of null and real recording counts`() = runTest {
        // Given -- one entry has no data (JSON-null counts), the other is a genuine result
        val recordingMbid = "rec-mbid-123"
        httpClient.givenJsonResponse(
            "popularity/recording",
            """[
                {
                    "recording_mbid": "other-mbid",
                    "total_listen_count": null,
                    "total_user_count": null
                },
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

        // Then -- the null entry is dropped and the genuine entry survives
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(99000L, data.listenCount)
        assertEquals(8500L, data.listenerCount)
    }

    @Test
    fun `enrich returns Success with a genuine zero recording listen count`() = runTest {
        // Given -- a real (non-null) total_listen_count of 0: documented as kept, not dropped
        httpClient.givenJsonResponse(
            "popularity/recording",
            """[
                {
                    "recording_mbid": "zero-plays-mbid",
                    "total_listen_count": 0,
                    "total_user_count": 0
                }
            ]""",
        )
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "zero-plays-mbid"),
            title = "Unpopular Track",
            artist = "Nobody",
        )

        // When -- enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then -- Success with a genuine zero, not filtered out like a null would be
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(0L, data.listenCount)
        assertEquals(0L, data.listenerCount)
    }

    @Test
    fun `enrich returns null listener count when only total_user_count is JSON-null`() = runTest {
        // Given -- LB has sent a genuine listen count alongside a null user count
        httpClient.givenJsonResponse(
            "popularity/recording",
            """[
                {
                    "recording_mbid": "listen-only-mbid",
                    "total_listen_count": 100,
                    "total_user_count": null
                }
            ]""",
        )
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "listen-only-mbid"),
            title = "Track",
            artist = "Artist",
        )

        // When -- enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then -- listenCount survives, listenerCount stays null rather than flattening to 0
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(100L, data.listenCount)
        assertEquals(null, data.listenerCount)
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
                    "recording_name": "Creep",
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
    fun `enrich falls back and returns NotFound when batch artist counts are all JSON-null`() = runTest {
        // Given -- LB has no data for the artist via the batch endpoint (JSON-null counts,
        // not zeros), and the top-recordings fallback also has nothing
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "popularity/artist",
            """[
                {
                    "artist_mbid": "$artistMbid",
                    "total_listen_count": null,
                    "total_user_count": null
                }
            ]""",
        )
        httpClient.givenJsonResponse("top-recordings-for-artist", "[]")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When -- enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then -- the null batch entry is dropped, the fallback is empty too -> NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich keeps the real entry from a mixed batch of null and real artist counts`() = runTest {
        // Given -- one entry has no data (JSON-null counts), the other is a genuine result
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "popularity/artist",
            """[
                {
                    "artist_mbid": "other-artist",
                    "total_listen_count": null,
                    "total_user_count": null
                },
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

        // Then -- the null entry is dropped and the genuine entry survives
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(500000L, data.listenCount)
        assertEquals(42000L, data.listenerCount)
    }

    @Test
    fun `enrich returns Success with a genuine zero artist listen count via batch endpoint`() = runTest {
        // Given -- a real (non-null) total_listen_count of 0: documented as kept, not dropped
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        httpClient.givenJsonResponse(
            "popularity/artist",
            """[
                {
                    "artist_mbid": "$artistMbid",
                    "total_listen_count": 0,
                    "total_user_count": 0
                }
            ]""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When -- enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then -- Success with a genuine zero, not filtered out like a null would be
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(0L, data.listenCount)
        assertEquals(0L, data.listenerCount)
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
                    "recording_name": "Unpopular Track",
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

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given — ListenBrainz API throws an IOException
        httpClient.givenIoException("listenbrainz.org")
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When — enriching for artist popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then — Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    @Test
    fun `enrich returns discography from top release groups`() = runTest {
        // Given
        httpClient.givenJsonResponse(
            "top-release-groups-for-artist",
            """[
                {"release_group_mbid":"rg-1","release_group_name":"OK Computer","artist_name":"Radiohead","total_listen_count":100000},
                {"release_group_mbid":"rg-2","release_group_name":"Kid A","artist_name":"Radiohead","total_listen_count":80000}
            ]""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val discography = success.data as EnrichmentData.Discography
        assertEquals(2, discography.albums.size)
        assertEquals("OK Computer", discography.albums[0].title)
        assertEquals("rg-1", discography.albums[0].identifiers.musicBrainzReleaseGroupId)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_DISCOGRAPHY when no release groups`() = runTest {
        // Given
        httpClient.givenJsonResponse("top-release-groups-for-artist", "[]")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("listenbrainz", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_DISCOGRAPHY without MBID`() = runTest {
        // Given — no MBID in identifiers
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `capabilities include ARTIST_DISCOGRAPHY at priority 50`() {
        // Then
        assertTrue(provider.capabilities.any { it.type == EnrichmentType.ARTIST_DISCOGRAPHY && it.priority == 50 })
    }

    @Test
    fun `capabilities do not include SIMILAR_ARTISTS -- no real ListenBrainz endpoint returns it`() {
        // Then -- pins the removal in #18: the invented lb-radio similar-artists route never
        // existed, so ListenBrainz no longer claims SIMILAR_ARTISTS; Deezer covers the type
        assertFalse(provider.capabilities.any { it.type == EnrichmentType.SIMILAR_ARTISTS })
    }

    @Test
    fun `enrich returns NotFound for SIMILAR_ARTISTS since the capability was removed`() = runTest {
        // Given
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then -- falls through the `when`'s else branch, no HTTP call made
        assertTrue(result is EnrichmentResult.NotFound)
    }
}
