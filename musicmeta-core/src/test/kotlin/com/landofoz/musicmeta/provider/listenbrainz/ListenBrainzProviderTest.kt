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
    fun `enrich returns top tracks ranked by listen count`() = runTest {
        // Given - the API returns a ranked list of the artist's top tracks by listen count
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

        // When - enriching for artist top tracks
        val result = provider.enrich(request, EnrichmentType.ARTIST_TOP_TRACKS)

        // Then - a Success with the top tracks ranked by listen count
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("listenbrainz", success.provider)
        assertEquals(0.95f, success.confidence, 0.01f)
        val topTracks = success.data as EnrichmentData.TopTracks
        assertEquals(2, topTracks.tracks.size)
        assertEquals("Creep", topTracks.tracks[0].title)
        assertEquals(50000L, topTracks.tracks[0].listenCount)
        assertEquals("abc", topTracks.tracks[0].identifiers.musicBrainzId)
        assertEquals(1, topTracks.tracks[0].rank)
        assertEquals("Karma Police", topTracks.tracks[1].title)
        assertEquals(45000L, topTracks.tracks[1].listenCount)
        assertEquals(2, topTracks.tracks[1].rank)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_POPULARITY when the request is not for an artist`() = runTest {
        // Given - an album request, whose MBID identifies a release group and not an artist
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "b1392450-e666-3926-a536-22c65998fcbd"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - artist popularity is asked of that request
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - NotFound, and the artist route was never asked for a release group
        assertTrue("a release-group MBID is not an artist MBID, got $result", result is EnrichmentResult.NotFound)
        assertTrue("nothing should have been requested", httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns NotFound when no artist MBID in identifiers`() = runTest {
        // Given - a request whose identifiers have no MusicBrainz ID
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When - enriching for artist popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - NotFound because ListenBrainz requires a MusicBrainz ID
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("listenbrainz", notFound.provider)
    }

    @Test
    fun `enrich returns NotFound when API returns empty results`() = runTest {
        // Given - the API returns an empty array
        val artistMbid = "00000000-0000-0000-0000-000000000000"
        httpClient.givenJsonResponse("listenbrainz.org", "[]")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Unknown Artist",
        )

        // When - enriching for artist popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - NotFound because no tracks were returned
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

        // When - enriching for artist popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - NotFound because the unstubbed request resolves to a 404
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich skips objects with missing recording_mbid`() = runTest {
        // Given - API returns array where some objects lack recording_mbid
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

        // When - enriching for artist top tracks
        val result = provider.enrich(request, EnrichmentType.ARTIST_TOP_TRACKS)

        // Then - only the track with a valid recording_mbid is included
        assertTrue(result is EnrichmentResult.Success)
        val topTracks = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, topTracks.tracks.size)
        assertEquals("Valid Track", topTracks.tracks[0].title)
        assertEquals("valid-mbid-123", topTracks.tracks[0].identifiers.musicBrainzId)
    }

    @Test
    fun `enrich returns track popularity via batch recording endpoint`() = runTest {
        // Given - batch recording popularity response
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

        // When - enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then - Success with track-level listen count and user count
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(99000L, data.listenCount)
        assertEquals(8500L, data.listenerCount)
    }

    @Test
    fun `enrich returns NotFound for TRACK_POPULARITY when no recording data`() = runTest {
        // Given - empty batch recording response
        httpClient.givenJsonResponse("popularity/recording", "[]")
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "no-data-mbid"),
            title = "Unknown",
            artist = "Nobody",
        )

        // When - enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_POPULARITY when recording counts are JSON-null`() = runTest {
        // Given - LB has no data for the recording: total_listen_count is JSON null, not 0
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

        // When - enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then - the null entry is dropped, leaving an empty list -> NotFound, not Success(0, 0)
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich keeps the real entry from a mixed batch of null and real recording counts`() = runTest {
        // Given - one entry has no data (JSON-null counts), the other is a genuine result
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

        // When - enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then - the null entry is dropped and the genuine entry survives
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(99000L, data.listenCount)
        assertEquals(8500L, data.listenerCount)
    }

    @Test
    fun `enrich returns Success with a genuine zero recording listen count`() = runTest {
        // Given - a real (non-null) total_listen_count of 0: documented as kept, not dropped
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

        // When - enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then - Success with a genuine zero, not filtered out like a null would be
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(0L, data.listenCount)
        assertEquals(0L, data.listenerCount)
    }

    @Test
    fun `enrich returns null listener count when only total_user_count is JSON-null`() = runTest {
        // Given - LB has sent a genuine listen count alongside a null user count
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

        // When - enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then - listenCount survives, listenerCount stays null rather than flattening to 0
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(100L, data.listenCount)
        assertEquals(null, data.listenerCount)
    }

    @Test
    fun `enrich returns artist popularity via batch artist endpoint`() = runTest {
        // Given - batch artist popularity response
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

        // When - enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - Success with batch artist popularity data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(500000L, data.listenCount)
        assertEquals(42000L, data.listenerCount)
    }

    @Test
    fun `enrich answers NotFound for ARTIST_POPULARITY when the batch endpoint holds nothing`() = runTest {
        // Given - an empty batch artist response and a populated top-recordings response
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

        // When - enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - NotFound: those recordings are ARTIST_TOP_TRACKS' answer, not a popularity payload
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when batch artist counts are all JSON-null`() = runTest {
        // Given - LB has no data for the artist via the batch endpoint (JSON-null counts, not zeros)
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
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When - enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - the null batch entry is dropped, leaving nothing to answer with -> NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich keeps the real entry from a mixed batch of null and real artist counts`() = runTest {
        // Given - one entry has no data (JSON-null counts), the other is a genuine result
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

        // When - enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - the null entry is dropped and the genuine entry survives
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(500000L, data.listenCount)
        assertEquals(42000L, data.listenerCount)
    }

    @Test
    fun `enrich returns Success with a genuine zero artist listen count via batch endpoint`() = runTest {
        // Given - a real (non-null) total_listen_count of 0: documented as kept, not dropped
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

        // When - enriching for ARTIST_POPULARITY
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - Success with a genuine zero, not filtered out like a null would be
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(0L, data.listenCount)
        assertEquals(0L, data.listenerCount)
    }

    @Test
    fun `enrich returns Success with zero listen count`() = runTest {
        // Given - API returns a track with total_listen_count of zero
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

        // When - enriching for artist top tracks
        val result = provider.enrich(request, EnrichmentType.ARTIST_TOP_TRACKS)

        // Then - Success with zero listen count (not filtered out)
        assertTrue(result is EnrichmentResult.Success)
        val topTracks = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, topTracks.tracks.size)
        assertEquals(0L, topTracks.tracks[0].listenCount)
    }

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given - ListenBrainz API throws an IOException
        httpClient.givenIoException("listenbrainz.org")
        val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = artistMbid),
            name = "Radiohead",
        )

        // When - enriching for artist popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then - Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    @Test
    fun `enrich returns discography from top release groups`() = runTest {
        // Given - the API returns a ranked list of the artist's top release groups
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

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - a Success with the release groups mapped to albums
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val discography = success.data as EnrichmentData.Discography
        assertEquals(2, discography.albums.size)
        assertEquals("OK Computer", discography.albums[0].title)
        assertEquals("rg-1", discography.albums[0].identifiers.musicBrainzReleaseGroupId)
    }

    @Test
    fun `enrich names an album whose title arrives nested under release_group`() = runTest {
        // Given - the shape the live route returns, captured 2026-09-03: no flat name keys
        httpClient.givenJsonResponse(
            "top-release-groups-for-artist",
            """[
                {
                    "release_group_mbid":"6e335887-60ba-38f0-95af-fae7774336bf",
                    "release_group":{"name":"In Rainbows","date":"2007-10-10","type":"Album"},
                    "artist":{"artists":[{"name":"Radiohead"}]},
                    "total_listen_count":26894695,
                    "total_user_count":274994
                }
            ]""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
            name = "Radiohead",
        )

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - the album carries its title rather than the blank a missing flat key leaves
        assertTrue(result is EnrichmentResult.Success)
        val discography = (result as EnrichmentResult.Success).data as EnrichmentData.Discography
        assertEquals("In Rainbows", discography.albums.single().title)
    }

    @Test
    fun `enrich skips a release group that carries no name in either shape`() = runTest {
        // Given - a constructed item with neither the nested nor the flat name key
        httpClient.givenJsonResponse(
            "top-release-groups-for-artist",
            """[
                {
                    "release_group_mbid":"6e335887-60ba-38f0-95af-fae7774336bf",
                    "total_listen_count":26894695
                }
            ]""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
            name = "Radiohead",
        )

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - NotFound rather than a Success carrying a blank-titled album
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_DISCOGRAPHY when no release groups`() = runTest {
        // Given - the API returns an empty array of release groups
        httpClient.givenJsonResponse("top-release-groups-for-artist", "[]")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
            name = "Radiohead",
        )

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - NotFound because no release groups were returned
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("listenbrainz", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_DISCOGRAPHY without MBID`() = runTest {
        // Given - no MBID in identifiers
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - NotFound because ListenBrainz requires a MusicBrainz ID
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `capabilities include ARTIST_DISCOGRAPHY at priority 50`() {
        // Given - the provider's declared capabilities list
        // When - searching it for ARTIST_DISCOGRAPHY
        // Then - the declared capabilities list ARTIST_DISCOGRAPHY at priority 50
        assertTrue(provider.capabilities.any { it.type == EnrichmentType.ARTIST_DISCOGRAPHY && it.priority == 50 })
    }
}
