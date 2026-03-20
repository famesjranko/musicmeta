package com.landofoz.musicmeta.provider.wikipedia

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

class WikipediaProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikipediaProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikipediaProvider(httpClient, RateLimiter(0L))
    }

    @Test
    fun `enrich returns biography from page summary`() = runTest {
        // Given
        val title = "Radiohead"
        httpClient.givenJsonResponse(
            "wikipedia.org",
            """{
                "title": "Radiohead",
                "extract": "Radiohead are an English rock band formed in 1985.",
                "description": "English rock band",
                "thumbnail": {
                    "source": "https://upload.wikimedia.org/thumb/radiohead.jpg/320px-radiohead.jpg"
                }
            }""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = title),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("wikipedia", success.provider)
        assertEquals(0.95f, success.confidence, 0.01f)
        val bio = success.data as EnrichmentData.Biography
        assertEquals("Radiohead are an English rock band formed in 1985.", bio.text)
        assertEquals("Wikipedia", bio.source)
        assertTrue(bio.thumbnailUrl != null)
    }

    @Test
    fun `enrich returns NotFound when no wikipediaTitle in identifiers`() = runTest {
        // Given
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("wikipedia", notFound.provider)
    }

    @Test
    fun `enrich returns NotFound for non-existent article`() = runTest {
        // Given - no response configured, so fetchJson returns null
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "NonExistentBandXYZ123"),
            name = "NonExistentBandXYZ123",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich includes article thumbnail URL`() = runTest {
        // Given
        val title = "Beck"
        val thumbnailUrl = "https://upload.wikimedia.org/thumb/beck.jpg/320px-beck.jpg"
        httpClient.givenJsonResponse(
            "wikipedia.org",
            """{
                "title": "Beck",
                "extract": "Beck Hansen is an American musician.",
                "description": "American musician",
                "thumbnail": {"source": "$thumbnailUrl"}
            }""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = title),
            name = "Beck",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals("Beck Hansen is an American musician.", bio.text)
        assertEquals(thumbnailUrl, bio.thumbnailUrl)
    }

    @Test
    fun `enrich returns NotFound when extract field is empty string`() = runTest {
        // Given — Wikipedia returns page summary with an empty extract
        httpClient.givenJsonResponse(
            "wikipedia.org",
            """{
                "title": "Obscure Band",
                "extract": "",
                "description": "Musical group",
                "thumbnail": {"source": "https://upload.wikimedia.org/thumb/img.jpg"}
            }""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Obscure Band"),
            name = "Obscure Band",
        )

        // When — enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then — NotFound because WikipediaApi returns null when extract is blank
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns biography without thumbnail when thumbnail object is missing`() = runTest {
        // Given — Wikipedia returns page summary without a thumbnail object
        httpClient.givenJsonResponse(
            "wikipedia.org",
            """{
                "title": "Some Artist",
                "extract": "Some Artist is a musician.",
                "description": "Musical artist"
            }""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Some Artist"),
            name = "Some Artist",
        )

        // When — enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then — Success with null thumbnailUrl since thumbnail object is absent
        assertTrue(result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals("Some Artist is a musician.", bio.text)
        assertEquals(null, bio.thumbnailUrl)
    }
}
