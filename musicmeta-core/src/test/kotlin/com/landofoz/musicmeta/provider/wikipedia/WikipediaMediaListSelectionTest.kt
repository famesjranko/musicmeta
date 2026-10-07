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
import org.junit.Test

/** Internal selection and rendering remain tested while public artist photos are quarantined. */
class WikipediaMediaListSelectionTest {

    private val httpClient = FakeHttpClient()

    private suspend fun selectedArtwork(title: String = "Radiohead"): EnrichmentData.Artwork? =
        WikipediaApi(httpClient, RateLimiter(0)).getPageMediaList(title).firstOrNull()
            ?.let(WikipediaMapper::toArtwork)

    private fun photoRequest(title: String = "Radiohead") = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(wikipediaTitle = title),
        name = title,
    )

    @Test
    fun `provider withholds photos even when a media route has a selected file`() = runTest {
        // Given - the article has a complete media-list response
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)
        val provider = WikipediaProvider(httpClient, RateLimiter(0))

        // When - a legacy caller requests its photo directly
        val result = provider.enrich(photoRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - neither a reusable photo nor an advertised capability escapes quarantine
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(provider.capabilities.none { it.type == EnrichmentType.ARTIST_PHOTO })
    }

    @Test
    fun `media parser picks the lead image at its largest offered scale`() = runTest {
        // Given - a media list whose lead image, an SVG-sourced icon and an audio file all appear
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - the 2x rendering wins, with the width its URL states
        assertTrue(result != null)
        val artwork = requireNotNull(result)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg" +
                "/1280px-RadioheadO2211125_composite.jpg",
            artwork.url,
        )
        assertEquals(1280, artwork.width)
    }

    @Test
    fun `media parser offers every scale the article renders in sizes`() = runTest {
        // Given - the lead image is offered at 1x and 2x
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - both renderings are listed, largest first, each labelled with its scale
        val artwork = requireNotNull(result)
        val sizes = artwork.sizes.orEmpty()
        assertEquals(listOf(1280, 500), sizes.map { it.width })
        assertEquals(listOf("2x", "1x"), sizes.map { it.label })
    }

    @Test
    fun `media parser strips the utm tracking parameters from every image URL`() = runTest {
        // Given - media-list sources carry Wikimedia's parser-attribution parameters
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - neither the chosen URL nor any listed size carries one
        val artwork = requireNotNull(result)
        assertTrue(!artwork.url.contains("utm_"))
        assertTrue(artwork.sizes.orEmpty().none { it.url.contains("utm_") })
    }

    @Test
    fun `media parser falls back to the first image when the article flags no lead`() = runTest {
        // Given - a gallery-style article where no item carries leadImage
        httpClient.givenJsonResponse("page/media-list", NO_LEAD_FLAG_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork("Metallica")

        // Then - the first surviving image in article order is used
        val artwork = requireNotNull(result)
        assertTrue(artwork.url.contains("Lars_Ulrich"))
    }

    @Test
    fun `media parser keeps a source whose URL states no rendered width`() = runTest {
        // Given - a src with no NNNpx- segment, so its width cannot be read
        httpClient.givenJsonResponse("page/media-list", NO_WIDTH_SEGMENT_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - it is kept with a null width, not dropped as if it were narrow
        assertTrue(result != null)
        val artwork = requireNotNull(result)
        assertNull(artwork.width)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/a/a1/RadioheadO2211125_composite.jpg",
            artwork.url,
        )
    }

    @Test
    fun `media parser passes through a source that is already absolute`() = runTest {
        // Given - a src carrying its own https scheme instead of the protocol-relative form
        httpClient.givenJsonResponse("page/media-list", ABSOLUTE_SOURCE_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - the URL is used as given, with no second scheme prepended
        val artwork = requireNotNull(result)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/" +
                "RadioheadO2211125_composite.jpg/500px-RadioheadO2211125_composite.jpg",
            artwork.url,
        )
    }

    @Test
    fun `media parser reports no height because the media list does not carry one`() = runTest {
        // Given - a media list item whose srcset states a width in the URL and no height anywhere
        httpClient.givenJsonResponse("page/media-list", RADIOHEAD_MEDIA_LIST_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - height is null rather than a number invented from the thumbnail
        val artwork = requireNotNull(result)
        assertNull(artwork.height)
    }

    @Test
    fun `media parser prefers the lead image over an earlier non-lead photograph`() = runTest {
        // Given - a media list whose lead image is not the first image in document order
        httpClient.givenJsonResponse("page/media-list", LEAD_IMAGE_LAST_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork("Metallica")

        // Then - the lead image wins on its flag, not on its position
        val artwork = requireNotNull(result)
        assertTrue(artwork.url.contains("Metallica_March_2024.jpg"))
    }

    @Test
    fun `media parser returns NotFound when every image is filtered out`() = runTest {
        // Given - a media list holding only an SVG-sourced icon and an audio file
        httpClient.givenJsonResponse("page/media-list", NON_PHOTO_ONLY_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - NotFound, because neither is a photograph of the artist
        assertNull(result)
    }

    @Test
    fun `media parser skips an image rendered below the minimum width`() = runTest {
        // Given - a narrow rendering ahead of a full-width photograph
        httpClient.givenJsonResponse("page/media-list", NARROW_THEN_WIDE_JSON)

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - the 60px rendering is skipped and the 500px photograph is returned
        val artwork = requireNotNull(result)
        assertTrue(artwork.url.contains("500px-Metallica_March_2024.jpg"))
    }

    @Test
    fun `media parser returns NotFound when the media list is empty`() = runTest {
        // Given - an article with no media at all
        httpClient.givenJsonResponse("page/media-list", """{"items":[]}""")

        // When - enriching for artist photo
        val result = selectedArtwork()

        // Then - NotFound
        assertNull(result)
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

        // captured 2026-08-12: GET /api/rest_v1/page/media-list/Metallica, items 3 and 0, with the
        // leadImage flag removed from both — synthetic, for the gallery-only article no sampled
        // page produced.
        val NO_LEAD_FLAG_JSON = """{
            "items": [
                {
                    "title": "File:Lars_Ulrich_(Metallica).jpg",
                    "leadImage": false,
                    "section_id": 1,
                    "type": "image",
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/f/f5/Lars_Ulrich_%28Metallica%29.jpg/500px-Lars_Ulrich_%28Metallica%29.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                },
                {
                    "title": "File:Metallica_March_2024.jpg",
                    "leadImage": false,
                    "section_id": 2,
                    "type": "image",
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/thumb/8/81/Metallica_March_2024.jpg/500px-Metallica_March_2024.jpg?utm_source=en.wikipedia.org&utm_campaign=parser&utm_content=thumbnail",
                            "scale": "1x"
                        }
                    ]
                }
            ]
        }""".trimIndent()

        // synthetic - a src pointing at the original file, which carries no /NNNpx- segment to read
        // a width from. Wikimedia serves such URLs; no sampled media-list item used one.
        val NO_WIDTH_SEGMENT_JSON = """{
            "items": [
                {
                    "title": "File:RadioheadO2211125_composite.jpg",
                    "leadImage": true,
                    "type": "image",
                    "srcset": [
                        {
                            "src": "//upload.wikimedia.org/wikipedia/commons/a/a1/RadioheadO2211125_composite.jpg",
                            "scale": "1x"
                        }
                    ]
                }
            ]
        }""".trimIndent()

        // synthetic - the same lead item with an absolute https src instead of the protocol-relative
        // form every sampled item used.
        val ABSOLUTE_SOURCE_JSON = """{
            "items": [
                {
                    "title": "File:RadioheadO2211125_composite.jpg",
                    "leadImage": true,
                    "type": "image",
                    "srcset": [
                        {
                            "src": "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg/500px-RadioheadO2211125_composite.jpg",
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
