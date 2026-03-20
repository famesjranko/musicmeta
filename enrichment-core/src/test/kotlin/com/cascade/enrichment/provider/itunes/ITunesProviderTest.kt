package com.cascade.enrichment.provider.itunes

import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.http.RateLimiter
import com.cascade.enrichment.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ITunesProviderTest {

    private val httpClient = FakeHttpClient()
    private val provider = ITunesProvider(httpClient, RateLimiter(0))

    @Test
    fun `enrich returns album art with upscaled URL`() = runTest {
        // Given — iTunes API returns a result for "OK Computer"
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — artwork URL is upscaled to 1200x1200
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("1200x1200bb"))
    }

    @Test
    fun `enrich returns NotFound when search returns no results`() = runTest {
        // Given — iTunes API returns empty results
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because no albums matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given — an artist-level request (iTunes only supports albums)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because iTunes doesn't handle artist requests
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich upscales artwork URL from 100x100 to 1200x1200`() = runTest {
        // Given — iTunes returns 100x100 artwork URL
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Artwork

        // Then — main URL uses 1200x1200, thumbnail preserves original 100x100
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/1200x1200bb.jpg",
            data.url,
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg",
            data.thumbnailUrl,
        )
    }

    @Test
    fun `enrich constructs correct search query`() = runTest {
        // Given — empty iTunes response (we only care about the outgoing URL)
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching triggers an HTTP request
        provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — query URL includes entity=album and the artist name
        val url = httpClient.requestedUrls.first()
        assertTrue(url.contains("entity=album"))
        assertTrue(url.contains("Radiohead"))
    }

    @Test
    fun `enrich returns NotFound when result has no artworkUrl100`() = runTest {
        // Given — iTunes result object is missing the artworkUrl100 field entirely
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":1,"results":[{
            "collectionName":"OK Computer",
            "artistName":"Radiohead"
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because artworkUrl is null after takeIfNotEmpty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when collectionName is empty string`() = runTest {
        // Given — iTunes result has empty collectionName but valid artwork
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":1,"results":[{
            "collectionName":"",
            "artistName":"Radiohead",
            "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — still returns Success because collectionName doesn't gate artwork extraction
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich returns NotFound when results field is missing from JSON`() = runTest {
        // Given — iTunes API returns JSON without a "results" array
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because optJSONArray("results") returns null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    companion object {
        val ITUNES_RESPONSE = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
            }]}
        """.trimIndent()
    }
}
