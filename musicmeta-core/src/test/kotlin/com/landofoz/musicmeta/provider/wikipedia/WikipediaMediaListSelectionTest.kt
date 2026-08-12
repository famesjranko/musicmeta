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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Which image an article's `page/media-list` response yields for `ARTIST_PHOTO`. */
class WikipediaMediaListSelectionTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikipediaProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikipediaProvider(httpClient, RateLimiter(0L))
    }

    private fun photoRequest(title: String = "Radiohead") = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(wikipediaTitle = title),
        name = title,
    )

    @Test
    fun `enrich picks the lead image from its srcset thumbnail`() = runTest {
        // Given - a media list whose lead image, an SVG-sourced icon and an audio file all appear
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)

        // When - enriching for artist photo
        val result = provider.enrich(photoRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - the lead photograph's 1x thumbnail is returned, with its rendered width
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg" +
                "/500px-RadioheadO2211125_composite.jpg" +
                "?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
            artwork.url,
        )
        assertEquals(500, artwork.width)
    }

    @Test
    fun `enrich reports no height because the media list does not carry one`() = runTest {
        // Given - a media list item whose srcset states a width in the URL and no height anywhere
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)

        // When - enriching for artist photo
        val result = provider.enrich(photoRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - height is null rather than a number invented from the thumbnail
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertNull(artwork.height)
    }

    @Test
    fun `enrich prefers the lead image over an earlier non-lead photograph`() = runTest {
        // Given - a media list whose lead image is not the first image in document order
        httpClient.givenJsonResponse("page/media-list", LEAD_IMAGE_LAST_JSON)

        // When - enriching for artist photo
        val result = provider.enrich(photoRequest("Metallica"), EnrichmentType.ARTIST_PHOTO)

        // Then - the lead image wins on its flag, not on its position
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(artwork.url.contains("Metallica_March_2024.jpg"))
    }

    @Test
    fun `enrich returns NotFound when every image is filtered out`() = runTest {
        // Given - a media list holding only an SVG-sourced icon and an audio file
        httpClient.givenJsonResponse("page/media-list", NON_PHOTO_ONLY_JSON)

        // When - enriching for artist photo
        val result = provider.enrich(photoRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound, because neither is a photograph of the artist
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich skips an image rendered below the minimum width`() = runTest {
        // Given - a narrow rendering ahead of a full-width photograph
        httpClient.givenJsonResponse("page/media-list", NARROW_THEN_WIDE_JSON)

        // When - enriching for artist photo
        val result = provider.enrich(photoRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - the 60px rendering is skipped and the 500px photograph is returned
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(artwork.url.contains("500px-Metallica_March_2024.jpg"))
    }

    @Test
    fun `enrich returns NotFound when the media list is empty`() = runTest {
        // Given - an article with no media at all
        httpClient.givenJsonResponse("page/media-list", """{"items":[]}""")

        // When - enriching for artist photo
        val result = provider.enrich(photoRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    private companion object {
        // captured 2026-08-12: GET /api/rest_v1/page/media-list/Radiohead, trimmed to the lead
        // image, one SVG-sourced icon and one audio item; other 21 items dropped.
        val RADIOHEAD_MEDIA_LIST_JSON = """{
            "revision": "1366786043",
            "tid": "693e0802-8bda-11f1-9102-822004d65ffd",
            "items": [
                {
                    "title": "File:RadioheadO2211125_composite.jpg",
                    "leadImage": true,
                    "section_id": 0,
                    "type": "image",
                    "showInGallery": true,
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg/500px-RadioheadO2211125_composite.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        },
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg/1280px-RadioheadO2211125_composite.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "2x"
                        }
                    ]
                },
                {
                    "title": "File:Gnome-mime-sound-openclipart.svg",
                    "leadImage": false,
                    "section_id": 3,
                    "type": "image",
                    "showInGallery": true,
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/8/87/Gnome-mime-sound-openclipart.svg/60px-Gnome-mime-sound-openclipart.svg.png?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                },
                {
                    "title": "File:Radiohead_-_Creep_(sample).ogg",
                    "leadImage": false,
                    "section_id": 3,
                    "type": "audio",
                    "audio_type": "generic",
                    "showInGallery": false
                }
            ]
        }""".trimIndent()

        // captured 2026-08-12: GET /api/rest_v1/page/media-list/Radiohead, the icon and audio items only.
        val NON_PHOTO_ONLY_JSON = """{
            "items": [
                {
                    "title": "File:Gnome-mime-sound-openclipart.svg",
                    "leadImage": false,
                    "type": "image",
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/8/87/Gnome-mime-sound-openclipart.svg/60px-Gnome-mime-sound-openclipart.svg.png?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                },
                {
                    "title": "File:Radiohead_-_Creep_(sample).ogg",
                    "leadImage": false,
                    "type": "audio",
                    "audio_type": "generic"
                }
            ]
        }""".trimIndent()

        // captured 2026-08-12: GET /api/rest_v1/page/media-list/Metallica, items 0 and 3, order
        // reversed — synthetic ordering, because every page sampled happened to list its lead
        // image first and selection must not rest on that.
        val LEAD_IMAGE_LAST_JSON = """{
            "items": [
                {
                    "title": "File:Lars_Ulrich_(Metallica).jpg",
                    "leadImage": false,
                    "section_id": 1,
                    "type": "image",
                    "showInGallery": true,
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/f/f5/Lars_Ulrich_%28Metallica%29.jpg/500px-Lars_Ulrich_%28Metallica%29.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                },
                {
                    "title": "File:Metallica_March_2024.jpg",
                    "leadImage": true,
                    "section_id": 0,
                    "type": "image",
                    "showInGallery": true,
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/8/81/Metallica_March_2024.jpg/500px-Metallica_March_2024.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                }
            ]
        }""".trimIndent()

        // captured 2026-08-12: GET /api/rest_v1/page/media-list/Metallica, item 0, preceded by a
        // synthetic 60px rendering of the same file — a narrow thumbnail with a photograph's title,
        // which no sampled page produced but the width rule exists to reject.
        val NARROW_THEN_WIDE_JSON = """{
            "items": [
                {
                    "title": "File:Metallica_March_2024_thumb.jpg",
                    "leadImage": false,
                    "type": "image",
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/8/81/Metallica_March_2024.jpg/60px-Metallica_March_2024.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                },
                {
                    "title": "File:Metallica_March_2024.jpg",
                    "leadImage": false,
                    "section_id": 0,
                    "type": "image",
                    "showInGallery": true,
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/8/81/Metallica_March_2024.jpg/500px-Metallica_March_2024.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                }
            ]
        }""".trimIndent()
    }
}
