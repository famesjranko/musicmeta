package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * [WikipediaProvider] holds no [com.landofoz.musicmeta.engine.ProviderCallScope] memo for the
 * Wikidata sitelink resolution it runs when a request carries no `wikipediaTitle`, so a fan-out
 * over ARTIST_BIO and ARTIST_PHOTO resolves the same Wikidata entity once per type.
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

    @Ignore(
        "Red now: WikipediaProvider resolves the Wikidata sitelink for a wikidataId twice (once " +
            "each for ARTIST_BIO, ARTIST_PHOTO) in one enrich() call when the request carries no " +
            "wikipediaTitle, where a memo would cost one. Remove this mark only once that count is " +
            "one; this assertion must go red first if it is not.",
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
