package com.cascade.enrichment.provider.fanarttv

import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentIdentifiers
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.http.RateLimiter
import com.cascade.enrichment.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FanartTvProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: FanartTvProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = FanartTvProvider(
            projectKey = "test-project-key",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
    }

    @Test
    fun `enrich returns artist photo`() = runTest {
        // Given
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/thumb1.jpg",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns artist background`() = runTest {
        // Given
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/bg1.jpg",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns artist logo`() = runTest {
        // Given
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_LOGO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/logo1.png",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns NotFound when no MBID`() = runTest {
        // Given - no musicBrainzId in identifiers
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when API returns no images`() = runTest {
        // Given - empty image arrays
        httpClient.givenJsonResponse("fanart.tv", EMPTY_IMAGES_JSON)
        val request = artistRequest()

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when API key is blank`() = runTest {
        // Given
        val blankProvider = FanartTvProvider(
            projectKey = "",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
        val request = artistRequest()

        // When
        val result = blankProvider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    private fun artistRequest() = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(
            musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
        ),
        name = "Radiohead",
    )

    @Test
    fun `enrich returns NotFound when image objects have no url field`() = runTest {
        // Given — Fanart.tv returns image arrays with objects missing the url field
        httpClient.givenJsonResponse("fanart.tv", """{
            "artistthumb": [{"id": "12345", "likes": "3"}],
            "artistbackground": [{"id": "67890"}],
            "hdmusiclogo": []
        }""")
        val request = artistRequest()

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — NotFound because extractUrls filters out objects without a valid url
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when albums key has no album objects`() = runTest {
        // Given — Fanart.tv returns an albums object that is empty (no album MBIDs)
        httpClient.givenJsonResponse("fanart.tv", """{
            "artistthumb": [],
            "artistbackground": [],
            "hdmusiclogo": [],
            "musicbanner": [],
            "albums": {}
        }""")
        val request = artistRequest()

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because albumCovers list is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    private companion object {
        val ARTIST_IMAGES_JSON = """
            {
              "artistthumb": [{"url": "https://assets.fanart.tv/fanart/thumb1.jpg"}],
              "artistbackground": [{"url": "https://assets.fanart.tv/fanart/bg1.jpg"}],
              "hdmusiclogo": [{"url": "https://assets.fanart.tv/fanart/logo1.png"}]
            }
        """.trimIndent()

        val EMPTY_IMAGES_JSON = """
            {
              "artistthumb": [],
              "artistbackground": [],
              "hdmusiclogo": [],
              "musicbanner": []
            }
        """.trimIndent()
    }
}
