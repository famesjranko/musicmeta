package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicBrainzProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `enrich album returns metadata for high-confidence match`() = runTest {
        // Given — MusicBrainz returns a release with score 98
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for genre (triggers identity resolution)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — success with MBID, release-group ID, and high confidence
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("musicbrainz", success.provider)
        assertEquals(0.98f, success.confidence, 0.01f)

        val data = success.data as EnrichmentData.IdentifierResolution
        assertEquals("abc123", data.musicBrainzId)
        assertEquals("group123", data.musicBrainzReleaseGroupId)
    }

    @Test
    fun `enrich album returns NotFound when score below threshold`() = runTest {
        // Given — MusicBrainz returns a low-score result (40)
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_LOW_SCORE)
        val request = EnrichmentRequest.forAlbum("Obscure Album", "Unknown")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because score is below minimum threshold
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich artist extracts Wikidata ID`() = runTest {
        // Given — MusicBrainz artist has a Wikidata relation
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIDATA)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for genre (triggers identity resolution)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Wikidata ID extracted from the relation URL
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.IdentifierResolution
        assertEquals("Q188451", data.wikidataId)
    }

    @Test
    fun `enrich artist extracts Wikipedia title`() = runTest {
        // Given — MusicBrainz artist has both Wikidata and Wikipedia relations
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIPEDIA)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Wikipedia title extracted from the en.wikipedia.org URL
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.IdentifierResolution
        assertEquals("Radiohead", data.wikipediaTitle)
    }

    @Test
    fun `enrich returns RateLimited on null response`() = runTest {
        // Given — no canned response configured (simulates network/rate-limit failure)
        val request = EnrichmentRequest.forAlbum("Test", "Test")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — RateLimited because null response indicates throttling
        assertTrue(result is EnrichmentResult.RateLimited)
    }

    @Test
    fun `enrich returns Error on malformed JSON`() = runTest {
        // Given — response has a release object missing the required 'id' field
        httpClient.givenJsonResponse(
            "release?query",
            """{"releases":[{"title":"NoId"}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Test", "Test")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Error with a descriptive message about the parse failure
        assertTrue(result is EnrichmentResult.Error)
        assertNotNull((result as EnrichmentResult.Error).message)
    }

    @Test
    fun `enrich track returns identifier for high-confidence match`() = runTest {
        // Given — MusicBrainz returns a recording match with score 95
        httpClient.givenJsonResponse("recording?query", RECORDING_SEARCH)
        val request = EnrichmentRequest.forTrack("Paranoid Android", "Radiohead")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — recording MBID resolved successfully
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.IdentifierResolution
        assertEquals("rec1", data.musicBrainzId)
    }

    companion object {
        private val RELEASE_SEARCH_HIGH_SCORE = """
            {
              "releases": [{
                "id": "abc123",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "def456", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {
                  "id": "group123",
                  "primary-type": "Album",
                  "tags": [{"name": "alternative rock", "count": 5}]
                },
                "cover-art-archive": {"front": true}
              }]
            }
        """.trimIndent()

        private val RELEASE_SEARCH_LOW_SCORE = """
            {
              "releases": [{
                "id": "low1",
                "score": 40,
                "title": "Something Else",
                "artist-credit": [{"artist": {"name": "Someone"}}]
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_WIKIDATA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "country": "GB",
                "tags": [{"name": "alternative rock", "count": 10}],
                "relations": [{
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }]
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "tags": [{"name": "rock", "count": 5}],
                "relations": [
                  {
                    "type": "wikidata",
                    "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                  },
                  {
                    "type": "wikipedia",
                    "url": {"resource": "https://en.wikipedia.org/wiki/Radiohead"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val RECORDING_SEARCH = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 95,
                "title": "Paranoid Android",
                "isrcs": ["GBAYE9700100"],
                "tags": [{"name": "alternative rock", "count": 3}]
              }]
            }
        """.trimIndent()
    }
}
