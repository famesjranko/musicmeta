package com.landofoz.musicmeta.provider.lastfm

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
        assertEquals("abc123", similar[0].musicBrainzId)
        assertEquals("Muse", similar[1].name)
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

    private companion object {
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
