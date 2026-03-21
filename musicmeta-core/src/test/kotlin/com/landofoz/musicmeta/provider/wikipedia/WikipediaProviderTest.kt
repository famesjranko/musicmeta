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
    fun `enrich returns artist photo from page media list`() = runTest {
        // Given — Wikipedia page summary and media-list return data
        httpClient.givenJsonResponse("page/summary", SUMMARY_JSON)
        httpClient.givenJsonResponse("page/media-list", MEDIA_LIST_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — success with Artwork from media list
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("wikipedia", success.provider)
        assertEquals(0.7f, success.confidence, 0.01f)
        val artwork = success.data as EnrichmentData.Artwork
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/radiohead.jpg", artwork.url)
        assertEquals(800, artwork.width)
        assertEquals(600, artwork.height)
    }

    @Test
    fun `enrich returns NotFound for artist photo when no suitable images`() = runTest {
        // Given — media list has only SVG images
        httpClient.givenJsonResponse("page/summary", SUMMARY_JSON)
        httpClient.givenJsonResponse("page/media-list", MEDIA_LIST_SVG_ONLY_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — NotFound because SVGs are filtered out
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich filters out SVG and icon images from media list`() = runTest {
        // Given — media list has SVGs, icons, and one valid JPEG
        httpClient.givenJsonResponse("page/summary", SUMMARY_JSON)
        httpClient.givenJsonResponse("page/media-list", MEDIA_LIST_MIXED_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — returns the valid JPEG, not SVGs or icons
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/valid_photo.jpg", artwork.url)
    }

    @Test
    fun `enrich returns NotFound for artist photo when media list is empty`() = runTest {
        // Given — media list has no items
        httpClient.givenJsonResponse("page/summary", SUMMARY_JSON)
        httpClient.givenJsonResponse("page/media-list", """{"items":[]}""")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — NotFound
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

    private companion object {
        val SUMMARY_JSON = """{
            "title": "Radiohead",
            "extract": "Radiohead are an English rock band.",
            "description": "English rock band"
        }""".trimIndent()

        val MEDIA_LIST_JSON = """{
            "items": [
                {
                    "type": "image",
                    "title": "File:Radiohead_live.jpg",
                    "original": {
                        "source": "https://upload.wikimedia.org/wikipedia/commons/radiohead.jpg",
                        "width": 800,
                        "height": 600
                    }
                }
            ]
        }""".trimIndent()

        val MEDIA_LIST_SVG_ONLY_JSON = """{
            "items": [
                {
                    "type": "image",
                    "title": "File:Flag_of_England.svg",
                    "original": {
                        "source": "https://upload.wikimedia.org/wikipedia/commons/flag.svg",
                        "width": 200,
                        "height": 100
                    }
                }
            ]
        }""".trimIndent()

        val MEDIA_LIST_MIXED_JSON = """{
            "items": [
                {
                    "type": "image",
                    "title": "File:Some_icon.svg",
                    "original": {
                        "source": "https://upload.wikimedia.org/wikipedia/commons/icon.svg",
                        "width": 50,
                        "height": 50
                    }
                },
                {
                    "type": "image",
                    "title": "File:Logo_icon_band.png",
                    "original": {
                        "source": "https://upload.wikimedia.org/wikipedia/commons/logo_icon.png",
                        "width": 200,
                        "height": 200
                    }
                },
                {
                    "type": "image",
                    "title": "File:Valid_photo.jpg",
                    "original": {
                        "source": "https://upload.wikimedia.org/wikipedia/commons/valid_photo.jpg",
                        "width": 640,
                        "height": 480
                    }
                }
            ]
        }""".trimIndent()
    }
}
