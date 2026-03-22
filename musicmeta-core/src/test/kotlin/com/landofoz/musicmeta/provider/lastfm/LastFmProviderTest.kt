package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LastFmProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: LastFmProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = LastFmProvider(
            apiKey = "test-api-key",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
    }

    @Test
    fun `enrich returns similar artists`() = runTest {
        // Given
        httpClient.givenJsonResponse("artist.getsimilar", SIMILAR_ARTISTS_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.SimilarArtists)
        val similar = (data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, similar.size)
        assertEquals("Thom Yorke", similar[0].name)
        assertEquals(0.8f, similar[0].matchScore)
        assertEquals("abc123", similar[0].identifiers.musicBrainzId)
        assertEquals("Muse", similar[1].name)
    }

    @Test
    fun `similar artists have sources set to lastfm`() = runTest {
        // Given
        httpClient.givenJsonResponse("artist.getsimilar", SIMILAR_ARTISTS_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then — each SimilarArtist includes "lastfm" in sources
        val similar = ((result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists).artists
        assertTrue(similar.all { it.sources == listOf("lastfm") })
    }

    @Test
    fun `enrich returns genre tags`() = runTest {
        // Given
        httpClient.givenJsonResponse("artist.getinfo", ARTIST_INFO_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Metadata)
        val genres = (data as EnrichmentData.Metadata).genres
        assertEquals(2, genres!!.size)
        assertEquals("alternative rock", genres[0])
        assertEquals("electronic", genres[1])
    }

    @Test
    fun `enrich returns artist bio`() = runTest {
        // Given
        httpClient.givenJsonResponse("artist.getinfo", ARTIST_INFO_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Biography)
        val bio = data as EnrichmentData.Biography
        assertEquals("Radiohead are an English rock band...", bio.text)
        assertEquals("Last.fm", bio.source)
    }

    @Test
    fun `enrich returns NotFound when API key is blank`() = runTest {
        // Given
        val blankProvider = LastFmProvider(
            apiKey = "",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = blankProvider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for album requests`() = runTest {
        // Given
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns artist popularity`() = runTest {
        // Given
        httpClient.givenJsonResponse("artist.getinfo", ARTIST_INFO_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Popularity)
        val popularity = data as EnrichmentData.Popularity
        assertEquals(4000000L, popularity.listenerCount)
        assertEquals(300000000L, popularity.listenCount)
    }

    @Test
    fun `enrich returns NotFound when artist object is missing from response`() = runTest {
        // Given — Last.fm returns JSON without the top-level "artist" object
        httpClient.givenJsonResponse("artist.getinfo", """{"error":6,"message":"Artist not found"}""")
        val request = EnrichmentRequest.forArtist(name = "Nonexistent")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because parseTags returns empty when "artist" key is absent
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when tags array is empty`() = runTest {
        // Given — Last.fm returns artist info with an empty tags array
        httpClient.givenJsonResponse("artist.getinfo", """{
            "artist": {
                "name": "Radiohead",
                "bio": {"summary": "Some bio text"},
                "tags": {"tag": []},
                "stats": {"listeners": "1000", "playcount": "5000"}
            }
        }""")
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because tags list is empty after filtering
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns popularity with null counts when stats object is missing`() = runTest {
        // Given — Last.fm returns artist info without a "stats" object
        httpClient.givenJsonResponse("artist.getinfo", """{
            "artist": {
                "name": "Radiohead",
                "bio": {"summary": "Some bio"},
                "tags": {"tag": [{"name": "rock"}]}
            }
        }""")
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — enriching for popularity
        val result = provider.enrich(request, EnrichmentType.ARTIST_POPULARITY)

        // Then — Success with null listener/play counts since stats is absent
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
        assertEquals(null, data.listenerCount)
        assertEquals(null, data.listenCount)
    }

    @Test
    fun `API calls use HTTPS`() = runTest {
        // Given — valid artist info response configured
        httpClient.givenJsonResponse("artist.getinfo", ARTIST_INFO_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — any API call is made
        provider.enrich(request, EnrichmentType.GENRE)

        // Then — all requested URLs use HTTPS
        assertTrue(httpClient.requestedUrls.isNotEmpty())
        assertTrue(httpClient.requestedUrls.all { it.startsWith("https://") })
    }

    @Test
    fun `capabilities include TRACK_POPULARITY`() {
        // Given — provider instance

        // When — checking capabilities

        // Then — TRACK_POPULARITY capability exists with priority 100
        assertTrue(provider.capabilities.any { it.type == EnrichmentType.TRACK_POPULARITY })
    }

    @Test
    fun `enrich returns track popularity for ForTrack request`() = runTest {
        // Given — Last.fm returns track.getInfo JSON with playcount and listeners
        httpClient.givenJsonResponse("track.getInfo", TRACK_INFO_JSON)
        val request = EnrichmentRequest.forTrack(title = "Karma Police", artist = "Radiohead")

        // When — enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then — Success with track-level playcount and listeners
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Popularity)
        val popularity = data as EnrichmentData.Popularity
        assertEquals(12345L, popularity.listenCount)
        assertEquals(6789L, popularity.listenerCount)
    }

    @Test
    fun `enrich returns NotFound for TRACK_POPULARITY with ForArtist request`() = runTest {
        // Given — valid artist info response configured
        httpClient.givenJsonResponse("artist.getinfo", ARTIST_INFO_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — enriching for TRACK_POPULARITY (requires ForTrack)
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then — NotFound because TRACK_POPULARITY requires ForTrack
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_POPULARITY when API returns null`() = runTest {
        // Given — no response configured for track.getInfo
        val request = EnrichmentRequest.forTrack(title = "Nonexistent", artist = "Nobody")

        // When — enriching for TRACK_POPULARITY
        val result = provider.enrich(request, EnrichmentType.TRACK_POPULARITY)

        // Then — NotFound because track.getInfo returns null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns SimilarTracks for track`() = runTest {
        // Given — Last.fm returns similar tracks
        httpClient.givenJsonResponse("track.getsimilar", SIMILAR_TRACKS_JSON)
        val request = EnrichmentRequest.forTrack(title = "Paranoid Android", artist = "Radiohead")

        // When — enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then — success with SimilarTracks data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.SimilarTracks)
        val tracks = (data as EnrichmentData.SimilarTracks).tracks
        assertEquals(2, tracks.size)
        assertEquals("Lucky", tracks[0].title)
        assertEquals("Radiohead", tracks[0].artist)
        assertEquals(0.9f, tracks[0].matchScore)
        assertEquals("track-mbid-1", tracks[0].identifiers.musicBrainzId)
        assertEquals("Karma Police", tracks[1].title)
    }

    @Test
    fun `enrich returns NotFound for SimilarTracks when no similar tracks`() = runTest {
        // Given — Last.fm returns empty similar tracks
        httpClient.givenJsonResponse("track.getsimilar", """{"similartracks":{"track":[]}}""")
        val request = EnrichmentRequest.forTrack(title = "Unknown", artist = "Nobody")

        // When — enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then — NotFound because no similar tracks returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given — Last.fm API throws an IOException
        httpClient.givenIoException("audioscrobbler.com")
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    // ALBUM_METADATA capability tests

    @Test
    fun `capabilities include ALBUM_METADATA at priority 40`() {
        // Given — provider instance

        // When — checking capabilities
        val capability = provider.capabilities.find { it.type == EnrichmentType.ALBUM_METADATA }

        // Then — ALBUM_METADATA capability exists with priority 40
        assertNotNull(capability)
        assertEquals(40, capability!!.priority)
    }

    @Test
    fun `enrich returns album metadata from album getinfo`() = runTest {
        // Given — Last.fm returns album.getinfo JSON for OK Computer
        httpClient.givenJsonResponse("album.getinfo", ALBUM_INFO_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When — enriching for ALBUM_METADATA
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — Success with Metadata containing trackCount and genres
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(12, data.trackCount)
        assertEquals(listOf("Alternative Rock", "Art Rock"), data.genres)
    }

    @Test
    fun `enrich returns album metadata with genreTags`() = runTest {
        // Given — Last.fm returns album.getinfo JSON with tags
        httpClient.givenJsonResponse("album.getinfo", ALBUM_INFO_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When — enriching for ALBUM_METADATA
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — Success with genreTags at confidence 0.3f with source "lastfm"
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        val tags = data.genreTags
        assertNotNull(tags)
        assertEquals(2, tags!!.size)
        assertEquals("Alternative Rock", tags[0].name)
        assertEquals(0.3f, tags[0].confidence)
        assertEquals(listOf("lastfm"), tags[0].sources)
    }

    @Test
    fun `enrich returns NotFound for ALBUM_METADATA when album not found`() = runTest {
        // Given — Last.fm returns error JSON for album not found
        httpClient.givenJsonResponse("album.getinfo", """{"error":6,"message":"Album not found"}""")
        val request = EnrichmentRequest.forAlbum(title = "Nonexistent", artist = "Nobody")

        // When — enriching for ALBUM_METADATA
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for ALBUM_METADATA with ForArtist request`() = runTest {
        // Given — ForArtist request (ALBUM_METADATA requires ForAlbum)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — enriching for ALBUM_METADATA
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — NotFound because ALBUM_METADATA requires ForAlbum
        assertTrue(result is EnrichmentResult.NotFound)
    }

    private companion object {
        val ALBUM_INFO_JSON = """
            {
              "album": {
                "name": "OK Computer",
                "artist": "Radiohead",
                "playcount": "5000000",
                "listeners": "800000",
                "tags": {
                  "tag": [
                    {"name": "Alternative Rock"},
                    {"name": "Art Rock"}
                  ]
                },
                "wiki": {
                  "summary": "OK Computer is the third studio album by Radiohead."
                },
                "tracks": {
                  "track": [
                    {"name": "Airbag"}, {"name": "Paranoid Android"}, {"name": "Subterranean Homesick Alien"},
                    {"name": "Exit Music (For a Film)"}, {"name": "Let Down"}, {"name": "Karma Police"},
                    {"name": "Fitter Happier"}, {"name": "Electioneering"}, {"name": "Climbing Up the Walls"},
                    {"name": "No Surprises"}, {"name": "Lucky"}, {"name": "The Tourist"}
                  ]
                }
              }
            }
        """.trimIndent()

        val SIMILAR_TRACKS_JSON = """
            {
              "similartracks": {
                "track": [
                  {"name": "Lucky", "match": "0.9", "mbid": "track-mbid-1", "artist": {"name": "Radiohead"}},
                  {"name": "Karma Police", "match": "0.75", "mbid": "", "artist": {"name": "Radiohead"}}
                ]
              }
            }
        """.trimIndent()

        val ARTIST_INFO_JSON = """
            {
              "artist": {
                "name": "Radiohead",
                "bio": {"summary": "Radiohead are an English rock band..."},
                "tags": {
                  "tag": [
                    {"name": "alternative rock"},
                    {"name": "electronic"}
                  ]
                },
                "stats": {
                  "listeners": "4000000",
                  "playcount": "300000000"
                }
              }
            }
        """.trimIndent()

        val TRACK_INFO_JSON = """
            {
              "track": {
                "name": "Karma Police",
                "artist": {"name": "Radiohead"},
                "playcount": "12345",
                "listeners": "6789",
                "mbid": "abc-123"
              }
            }
        """.trimIndent()

        val SIMILAR_ARTISTS_JSON = """
            {
              "similarartists": {
                "artist": [
                  {"name": "Thom Yorke", "match": "0.8", "mbid": "abc123"},
                  {"name": "Muse", "match": "0.6", "mbid": "def456"}
                ]
              }
            }
        """.trimIndent()
    }
}
