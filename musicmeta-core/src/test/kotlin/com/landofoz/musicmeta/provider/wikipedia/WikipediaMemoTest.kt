package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testkit.countMatching
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [WikipediaProvider] memoizes the Wikidata sitelink resolution it runs when a request carries no
 * `wikipediaTitle`, for the life of one [com.landofoz.musicmeta.engine.ProviderCallScope]: a fan-out
 * over ARTIST_BIO and ARTIST_PHOTO resolves the same Wikidata entity once for the call, not once
 * per type.
 */
class WikipediaMemoTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikipediaProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikipediaProvider(httpClient, RateLimiter(0L))
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    @Test
    fun `one artist's fanout resolves the Wikidata sitelink once, not once per type`() = runTest {
        // Given - an artist known only by wikidataId, whose sitelink resolves an article both
        // ARTIST_BIO and ARTIST_PHOTO can answer from
        httpClient.givenJsonResponse("wikidata.org", SITELINKS_JSON)
        httpClient.givenJsonResponse("action=query", EXTRACT_JSON)
        httpClient.givenJsonResponse("page/media-list", MEDIA_LIST_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q123"),
            name = "Radiohead",
        )

        // When - both types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO))

        // Then - the sitelink was resolved once for the whole call, not once per type
        httpClient.assertNoUrlRequestedTwice()
    }

    @Test
    fun `an entity with no English sitelink is resolved once, not once per type`() = runTest {
        // Given - a Wikidata entity carrying no enwiki sitelink
        httpClient.givenJsonResponse("wikidata.org", NO_SITELINK_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q999"),
            name = "Obscure Artist",
        )

        // When - both types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO))

        // Then - the miss was resolved once, not once per type
        assertEquals(1, httpClient.countMatching("wikidata.org"))
    }

    @Test
    fun `forceRefresh re-resolves the sitelink, not the previous call's memo`() = runTest {
        // Given - an entity whose sitelink is already resolved once
        httpClient.givenJsonResponse("wikidata.org", SITELINKS_JSON)
        httpClient.givenJsonResponse("action=query", EXTRACT_JSON)
        httpClient.givenJsonResponse("page/media-list", MEDIA_LIST_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q123"),
            name = "Radiohead",
        )
        val eng = engine()
        eng.enrich(request, setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO))

        // When - the same engine is asked again with forceRefresh, bypassing its cache
        eng.enrich(request, setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO), forceRefresh = true)

        // Then - the memo did not survive into the second call: one resolution per call, two total
        assertEquals(2, httpClient.countMatching("wikidata.org"))
    }

    private companion object {
        val SITELINKS_JSON = """
            {
              "entities": {
                "Q123": {
                  "sitelinks": {
                    "enwiki": {"title": "Radiohead"}
                  }
                }
              }
            }
        """.trimIndent()

        val NO_SITELINK_JSON = """
            {
              "entities": {
                "Q999": {
                  "sitelinks": {}
                }
              }
            }
        """.trimIndent()

        val EXTRACT_JSON = """
            {
              "query": {
                "pages": [
                  {"pageid": 38252, "ns": 0, "title": "Radiohead", "extract": "Radiohead are an English rock band."}
                ]
              }
            }
        """.trimIndent()

        val MEDIA_LIST_JSON = """
            {
              "items": [
                {
                  "leadImage": true,
                  "showInGallery": true,
                  "srcset": [
                    {"src": "//upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/1280px-Radiohead.jpg", "scale": "2x"}
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
