package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
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
    fun `enrich returns biography from the article extract`() = runTest {
        // Given - the Action API returns the lead extract, a thumbnail and the page properties
        httpClient.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - success with a Biography built from the extract and thumbnail
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("wikipedia", success.provider)
        assertEquals(0.95f, success.confidence, 0.01f)
        val bio = success.data as EnrichmentData.Biography
        assertEquals(RADIOHEAD_EXTRACT_TEXT, bio.text)
        assertEquals("Wikipedia", bio.source)
        assertTrue(bio.thumbnailUrl != null)
    }

    @Test
    fun `enrich keeps the parentheticals the extract carries`() = runTest {
        // Given - Portishead's lead opens with an IPA pronunciation the summary endpoint stripped
        httpClient.givenJsonResponse("wikipedia.org", PORTISHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Portishead (band)"),
            name = "Portishead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - the bio reads as the article does, parenthetical included
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertTrue(bio.text.startsWith("Portishead ( PORT-iss-HED) are an English electronic band"))
    }

    @Test
    fun `enrich requests the extract, thumbnail and page properties in one call`() = runTest {
        // Given - a page whose bio is available
        httpClient.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - exactly one Wikipedia request, carrying all three properties
        val wikipediaUrls = httpClient.requestedUrls.filter { it.contains("en.wikipedia.org") }
        assertEquals(1, wikipediaUrls.size)
        assertTrue(wikipediaUrls.single().contains("prop=extracts%7Cpageimages%7Cpageprops"))
    }

    @Test
    fun `enrich returns NotFound when no wikipediaTitle in identifiers`() = runTest {
        // Given - identifiers carry no wikipediaTitle and no wikidataId to resolve one
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - NotFound because there is no title to look up
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("wikipedia", notFound.provider)
    }

    @Test
    fun `enrich returns NotFound for non-existent article`() = runTest {
        // Given - the Action API answers 200 with the page flagged missing
        httpClient.givenJsonResponse("wikipedia.org", MISSING_PAGE_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "NoSuchPageZZZQQ"),
            name = "NoSuchPageZZZQQ",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - NotFound, not an empty-extract Success
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for a disambiguation page`() = runTest {
        // Given - "Genesis" resolves to a disambiguation page, marked by pageprops.disambiguation
        httpClient.givenJsonResponse("wikipedia.org", DISAMBIGUATION_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Genesis"),
            name = "Genesis",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - NotFound, because "Genesis may refer to:" is not a biography
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich includes article thumbnail URL`() = runTest {
        // Given - the Action API returns a pageimages thumbnail alongside the extract
        httpClient.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - the thumbnail source is carried through to the Biography
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals(RADIOHEAD_THUMBNAIL_URL, bio.thumbnailUrl)
    }

    @Test
    fun `enrich returns biography without thumbnail when the page has no page image`() = runTest {
        // Given - The Books' article carries no pageimages thumbnail
        httpClient.givenJsonResponse("wikipedia.org", NO_THUMBNAIL_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "The Books"),
            name = "The Books",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - Success with a null thumbnailUrl
        assertTrue(result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals(null, bio.thumbnailUrl)
    }

    @Test
    fun `enrich returns NotFound when extract field is empty string`() = runTest {
        // Given - a page that exists but whose lead extract is empty
        httpClient.givenJsonResponse("wikipedia.org", EMPTY_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Obscure Band"),
            name = "Obscure Band",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - NotFound because WikipediaApi returns null when the extract is blank
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error when the Action API reports maxlag inside a 200`() = runTest {
        // Given - the Action API answers 200 with an error body, its way of shedding load
        httpClient.givenJsonResponse("wikipedia.org", MAXLAG_ERROR_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - a retryable Error, never NotFound: the article exists and was not asked about
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich strips the utm tracking parameters from the bio thumbnail`() = runTest {
        // Given - pageimages thumbnails arrive with Wikimedia's utm_ attribution parameters
        httpClient.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - the URL handed to a consumer carries none of them
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals(RADIOHEAD_THUMBNAIL_URL, bio.thumbnailUrl)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API fails`() = runTest {
        // Given - simulate an IOException from the HTTP layer
        httpClient.givenIoException("wikipedia.org")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - Error with NETWORK kind because IOException maps to ErrorKind.NETWORK
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals("wikipedia", result.provider)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when the Wikidata sitelink lookup fails`() = runTest {
        // Given - no wikipediaTitle, so the title comes from the Wikidata sitelink call, which throws
        httpClient.givenIoException("wikidata.org")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q123"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - Error with NETWORK kind, not a propagated IOException
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals("wikipedia", result.provider)
    }

    @Test
    fun `enrich resolves the title from Wikidata sitelinks when no wikipediaTitle is given`() = runTest {
        // Given - Wikidata returns an enwiki sitelink and Wikipedia returns that page's extract
        httpClient.givenJsonResponse("wikidata.org", SITELINKS_JSON)
        httpClient.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q123"),
            name = "Radiohead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - Success, and the resolved title was used for the extract request
        assertTrue(result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals(RADIOHEAD_EXTRACT_TEXT, bio.text)
        assertTrue(httpClient.requestedUrls.any { it.contains("wikipedia.org") && it.contains("Radiohead") })
    }

    @Test
    fun `enrich returns album description from the article extract`() = runTest {
        // Given - album requests reuse the same title-resolution and Biography mapping as ARTIST_BIO
        httpClient.givenJsonResponse("wikipedia.org", OK_COMPUTER_EXTRACT_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "OK Computer"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for ALBUM_DESCRIPTION
        val result = provider.enrich(request, EnrichmentType.ALBUM_DESCRIPTION)

        // Then - success with the page extract mapped into a Biography
        assertTrue(result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals(OK_COMPUTER_EXTRACT_TEXT, bio.text)
        assertEquals("Wikipedia", bio.source)
    }

    @Test
    fun `enrich resolves album description title from Wikidata sitelinks`() = runTest {
        // Given - album identifiers carry only a wikidataId, resolved from the release-group relation
        httpClient.givenJsonResponse("wikidata.org", SITELINKS_JSON)
        httpClient.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q123"),
            title = "Radiohead",
            artist = "Radiohead",
        )

        // When - enriching for ALBUM_DESCRIPTION
        val result = provider.enrich(request, EnrichmentType.ALBUM_DESCRIPTION)

        // Then - success, the title resolved via the Wikidata sitelink lookup
        assertTrue(result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertEquals(RADIOHEAD_EXTRACT_TEXT, bio.text)
    }

    @Test
    fun `enrich returns NotFound for ALBUM_DESCRIPTION when no identifiers resolve a title`() = runTest {
        // Given - no wikipediaTitle and no wikidataId
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(),
            title = "Unknown Album",
            artist = "Unknown Artist",
        )

        // When - enriching for ALBUM_DESCRIPTION
        val result = provider.enrich(request, EnrichmentType.ALBUM_DESCRIPTION)

        // Then - NotFound because no title could be resolved
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich picks the enwiki sitelink, not the first one, when the entity has many languages`() = runTest {
        // Given - Q191352's sitelinks with arwiki, frwiki and dewiki ahead of enwiki in map order
        httpClient.givenJsonResponse("wikidata.org", MULTI_LANGUAGE_SITELINKS_JSON)
        httpClient.givenJsonResponse("wikipedia.org", PORTISHEAD_EXTRACT_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q191352"),
            name = "Portishead",
        )

        // When - enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - the English article was requested, never the French or Arabic one
        assertTrue(result is EnrichmentResult.Success)
        val wikipediaUrls = httpClient.requestedUrls.filter { it.contains("wikipedia.org") }
        assertTrue(wikipediaUrls.any { it.contains("Portishead%20%28band%29") })
        assertTrue(wikipediaUrls.none { it.contains("groupe") })
    }

    private companion object {
        const val RADIOHEAD_EXTRACT_TEXT =
            "Radiohead are an English rock band formed in Abingdon, Oxfordshire, in 1985. " +
                "The band members are Thom Yorke (vocals, guitar, keyboards); the brothers " +
                "Jonny Greenwood (guitar, keyboards, other instruments) and Colin Greenwood (bass)."

        /** As captured, tracking parameters and all. */
        const val RADIOHEAD_THUMBNAIL_RAW =
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg" +
                "/330px-RadioheadO2211125_composite.jpg" +
                "?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"

        /** The same URL as a consumer receives it. */
        const val RADIOHEAD_THUMBNAIL_URL =
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg" +
                "/330px-RadioheadO2211125_composite.jpg"

        const val OK_COMPUTER_EXTRACT_TEXT =
            "OK Computer is the third studio album by the English rock band Radiohead, " +
                "released on 21 May 1997."

        // captured 2026-08-12: GET /w/api.php?action=query&prop=extracts|pageimages|pageprops
        // &exintro&explaintext&titles=Radiohead, extract cut after its second sentence.
        val RADIOHEAD_EXTRACT_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "pageid": 38252,
                        "ns": 0,
                        "title": "Radiohead",
                        "extract": "$RADIOHEAD_EXTRACT_TEXT",
                        "thumbnail": {
                            "source": "$RADIOHEAD_THUMBNAIL_RAW",
                            "width": 320,
                            "height": 116
                        },
                        "pageprops": {
                            "page_image_free": "RadioheadO2211125_composite.jpg",
                            "wikibase-badge-Q17437796": "",
                            "wikibase-shortdesc": "English rock band",
                            "wikibase_item": "Q44190"
                        }
                    }
                ]
            }
        }""".trimIndent()

        // captured 2026-08-12: same query, titles=Portishead (band), extract cut after its first
        // clause. The " ( PORT-iss-HED)" is the API's plain-text rendering of the IPA parenthetical.
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
                            "source": "https://upload.wikimedia.org/wikipedia/commons/thumb/9/92/Portishead13b.jpg/330px-Portishead13b.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
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

        // captured 2026-08-12: same query, titles=OK Computer, extract cut after its first sentence.
        // This page carries no pageimages thumbnail, only a non-free page_image pageprop.
        val OK_COMPUTER_EXTRACT_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "pageid": 84636,
                        "ns": 0,
                        "title": "OK Computer",
                        "extract": "$OK_COMPUTER_EXTRACT_TEXT",
                        "pageprops": {
                            "displaytitle": "<i>OK Computer</i>",
                            "page_image": "Radioheadokcomputer.png",
                            "toc": "",
                            "wikibase-shortdesc": "1997 studio album by Radiohead",
                            "wikibase_item": "Q202996"
                        }
                    }
                ]
            }
        }""".trimIndent()

        // captured 2026-08-12: same query, titles=The Books, extract cut after its first clause.
        val NO_THUMBNAIL_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "pageid": 1573283,
                        "ns": 0,
                        "title": "The Books",
                        "extract": "The Books were an American-Dutch duo, formed in New York City in 1999.",
                        "pageprops": {
                            "defaultsort": "Books, The",
                            "wikibase-shortdesc": "American-Dutch experimental-music duo",
                            "wikibase_item": "Q2409957"
                        }
                    }
                ]
            }
        }""".trimIndent()

        // captured 2026-08-12: same query, titles=Genesis — a disambiguation page, marked by the
        // empty-valued pageprops.disambiguation key.
        val DISAMBIGUATION_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "pageid": 488971,
                        "ns": 0,
                        "title": "Genesis",
                        "extract": "Genesis may refer to:",
                        "pageprops": {
                            "disambiguation": "",
                            "toc": "",
                            "wikibase-shortdesc": "Topics referred to by the same term",
                            "wikibase_item": "Q29244"
                        }
                    }
                ]
            }
        }""".trimIndent()

        // captured 2026-08-12: GET /w/api.php?action=query&prop=extracts&titles=Radiohead&maxlag=-1
        // — the Action API's own failure shape, served with HTTP 200. The `info` host and lag are
        // as returned; `docref` dropped.
        val MAXLAG_ERROR_JSON = """{
            "error": {
                "code": "maxlag",
                "info": "Waiting for 10.64.48.169: 0.385659 seconds lagged.",
                "host": "10.64.48.169",
                "lag": 0.385659,
                "type": "db"
            },
            "servedby": "mw-api-ext.eqiad.main-5ff7ffbb74-s5xdf"
        }""".trimIndent()

        // captured 2026-08-12: same query, titles=NoSuchPageZZZQQ.
        val MISSING_PAGE_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "ns": 0,
                        "title": "NoSuchPageZZZQQ",
                        "missing": true
                    }
                ]
            }
        }""".trimIndent()

        // synthetic - an existing page whose lead extract came back empty; no sampled title
        // produced one, and the parser must not turn it into a blank biography.
        val EMPTY_EXTRACT_JSON = """{
            "batchcomplete": true,
            "query": {
                "pages": [
                    {
                        "pageid": 999999,
                        "ns": 0,
                        "title": "Obscure Band",
                        "extract": "",
                        "pageprops": {
                            "wikibase-shortdesc": "Musical group",
                            "wikibase_item": "Q999999"
                        }
                    }
                ]
            }
        }""".trimIndent()

        /** enwiki is deliberately last: selection must be by key, not by map order. */
        val MULTI_LANGUAGE_SITELINKS_JSON = """{
            "entities": {
                "Q191352": {
                    "sitelinks": {
                        "arwiki": { "title": "بورتس هيد" },
                        "frwiki": { "title": "Portishead (groupe)" },
                        "dewiki": { "title": "Portishead (Band)" },
                        "enwiki": { "title": "Portishead (band)" }
                    }
                }
            }
        }""".trimIndent()

        val SITELINKS_JSON = """{
            "entities": {
                "Q123": {
                    "sitelinks": {
                        "enwiki": { "title": "Radiohead" }
                    }
                }
            }
        }""".trimIndent()
    }
}
