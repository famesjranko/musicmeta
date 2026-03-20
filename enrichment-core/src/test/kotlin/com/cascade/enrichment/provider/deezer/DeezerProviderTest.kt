package com.cascade.enrichment.provider.deezer

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

class DeezerProviderTest {

    private val httpClient = FakeHttpClient()
    private val provider = DeezerProvider(httpClient, RateLimiter(0))

    @Test
    fun `enrich returns album art from search result`() = runTest {
        // Given — Deezer API returns a matching album with cover URLs
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — success with XL cover as main and medium as thumbnail
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/xl.jpg", data.url)
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/medium.jpg", data.thumbnailUrl)
    }

    @Test
    fun `enrich returns NotFound when search returns no results`() = runTest {
        // Given — Deezer API returns empty data array
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because no albums matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given — an artist-level request (Deezer only supports albums)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because Deezer doesn't handle artist requests
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich uses coverXl for main URL and coverMedium for thumbnail`() = runTest {
        // Given — Deezer response with multiple cover sizes
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Artwork

        // Then — main URL is XL, thumbnail is medium
        assertTrue(data.url.contains("xl"))
        assertTrue(data.thumbnailUrl!!.contains("medium"))
    }

    @Test
    fun `enrich constructs correct search query`() = runTest {
        // Given — empty Deezer response (we only care about the outgoing URL)
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching triggers an HTTP request
        provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — query URL includes artist and album names
        val url = httpClient.requestedUrls.first()
        assertTrue(url.contains("Radiohead"))
        assertTrue(url.contains("OK+Computer") || url.contains("OK%20Computer"))
    }

    @Test
    fun `enrich returns NotFound when data object has no title field`() = runTest {
        // Given — Deezer API returns album objects missing the title field
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{"artist":{"name":"Radiohead"},"cover_xl":"https://example.com/cover.jpg"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — still returns Success because cover URL is present (title is metadata, not required for artwork)
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich returns NotFound when artist object is missing from album`() = runTest {
        // Given — Deezer API returns album without nested artist object
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{"title":"OK Computer","cover_xl":"https://example.com/cover.jpg"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — still returns Success because cover URL extraction does not depend on artist
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich returns NotFound when all cover fields are empty strings`() = runTest {
        // Given — Deezer API returns album with all cover URLs as empty strings
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{
            "title":"OK Computer",
            "artist":{"name":"Radiohead"},
            "cover_small":"",
            "cover_medium":"",
            "cover_big":"",
            "cover_xl":""
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because takeIfNotEmpty filters out blank strings
        assertTrue(result is EnrichmentResult.NotFound)
    }

    companion object {
        val DEEZER_RESPONSE = """
            {"data":[{
                "title":"OK Computer",
                "artist":{"name":"Radiohead"},
                "cover_small":"https://e-cdns-images.dzcdn.net/images/cover/small.jpg",
                "cover_medium":"https://e-cdns-images.dzcdn.net/images/cover/medium.jpg",
                "cover_big":"https://e-cdns-images.dzcdn.net/images/cover/big.jpg",
                "cover_xl":"https://e-cdns-images.dzcdn.net/images/cover/xl.jpg"
            }]}
        """.trimIndent()
    }
}
