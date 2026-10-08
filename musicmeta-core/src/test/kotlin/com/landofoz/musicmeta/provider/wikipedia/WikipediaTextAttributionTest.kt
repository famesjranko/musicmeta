package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The facts Wikipedia states about its article text ride on the `Biography`: the article, its
 * language and the licence. They describe the text only, and a missing one never costs the text.
 */
class WikipediaTextAttributionTest {

    private fun summary(title: String, thumbnailUrl: String? = THUMBNAIL_URL) = WikipediaSummary(
        title = title,
        extract = "Portishead are an English electronic band formed in 1991 in Bristol.",
        description = "English band",
        thumbnailUrl = thumbnailUrl,
        wikibaseItem = "Q191352",
    )

    @Test
    fun `a biography carries the article title, language, URL and licence`() {
        // Given - a summary for an article whose title has a space and parentheses
        val summary = summary("Portishead (band)")

        // When - it is mapped to a biography
        val biography = WikipediaMapper.toBiography(summary)

        // Then - the attribution names the article, English, its en.wikipedia.org URL and CC BY-SA 4.0
        assertEquals(
            Attribution(
                title = "Portishead (band)",
                language = "en",
                sourceUrl = "https://en.wikipedia.org/wiki/Portishead_%28band%29",
                licence = "CC BY-SA 4.0",
                licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
            ),
            biography.attribution,
        )
    }

    @Test
    fun `the thumbnail is returned as the upstream gave it and does not change the text attribution`() {
        // Given - two summaries for one article, one with a thumbnail and one without
        val withThumbnail = summary("Portishead (band)")
        val withoutThumbnail = summary("Portishead (band)", thumbnailUrl = null)

        // When - both are mapped to biographies
        val withBiography = WikipediaMapper.toBiography(withThumbnail)
        val withoutBiography = WikipediaMapper.toBiography(withoutThumbnail)

        // Then - the thumbnail is passed through untouched, and the text attribution is the same either way
        assertEquals(THUMBNAIL_URL, withBiography.thumbnailUrl)
        assertNull(withoutBiography.thumbnailUrl)
        assertEquals(withoutBiography.attribution, withBiography.attribution)
    }

    @Test
    fun `a summary with no title keeps the biography and the licence and leaves the article facts null`() {
        // Given - a summary whose title is blank, so no article can be named
        val summary = summary(title = " ", thumbnailUrl = null)

        // When - it is mapped to a biography
        val biography = WikipediaMapper.toBiography(summary)

        // Then - the text survives, the licence is kept, and the title and article URL are null
        assertEquals(summary.extract, biography.text)
        val attribution = biography.attribution
        assertNotNull(attribution)
        assertNull(attribution?.title)
        assertNull(attribution?.sourceUrl)
        assertEquals("CC BY-SA 4.0", attribution?.licence)
    }

    @Test
    fun `the provider returns a biography with attribution from a captured Action API response`() = runTest {
        // Given - the Action API response captured for Portishead, served for the article title
        val http = FakeHttpClient().also { it.givenJsonResponse("wikipedia.org", PORTISHEAD_EXTRACT_JSON) }
        val provider = WikipediaProvider(http, RateLimiter(0L))
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Portishead (band)"),
            name = "Portishead",
        )

        // When - the provider enriches ARTIST_BIO
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - a Success whose biography carries the article URL and licence beside the text
        val biography = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals("https://en.wikipedia.org/wiki/Portishead_%28band%29", biography.attribution?.sourceUrl)
        assertEquals("CC BY-SA 4.0", biography.attribution?.licence)
        assertEquals(THUMBNAIL_URL, biography.thumbnailUrl)
    }

    private companion object {
        const val THUMBNAIL_URL = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/92/Portishead13b.jpg/330px-Portishead13b.jpg"

        // captured 2026-08-12: GET /w/api.php?action=query&prop=extracts|pageimages|pageprops
        // &exintro&explaintext&titles=Portishead (band), extract cut after its first clause.
        val PORTISHEAD_EXTRACT_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "pageid": 87731,
                        "ns": 0,
                        "title": "Portishead (band)",
                        "extract": "Portishead ( PORT-iss-HED) are an English electronic band formed in 1991 in Bristol.",
                        "thumbnail": {
                            "source": "$THUMBNAIL_URL?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
                            "width": 320,
                            "height": 148
                        },
                        "pageprops": {
                            "page_image_free": "Portishead13b.jpg",
                            "wikibase-shortdesc": "English band",
                            "wikibase_item": "Q191352"
                        }
                    }
                ]
            }
        }""".trimIndent()
    }
}
