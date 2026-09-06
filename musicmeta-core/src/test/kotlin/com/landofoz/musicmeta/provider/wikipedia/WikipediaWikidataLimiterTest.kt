package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.testkit.UpstreamPools
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `wikidata.org` is one host and gets one queue, whichever provider is asking.
 *
 * [WikipediaProvider] reaches two hosts — Wikipedia for the article, Wikidata for the sitelink that
 * resolves its title — so it is the one provider here whose limiters are not interchangeable. Its
 * third constructor argument is the *Wikidata* one, and `withDefaultProviders()` hands it the same
 * instance [WikidataProvider] holds. Two instances would be legal Kotlin, compile, pass every other
 * test, and serve one host at twice the rate agreed with it.
 *
 * The clock is the test scheduler's, so what is asserted is the delay the limiter asked for and not
 * how long this machine took to run it.
 */
class WikipediaWikidataLimiterTest {

    @Test
    fun `both providers queue on one Wikidata limiter, so their requests are spaced`() = runTest {
        // Given - the two providers as withDefaultProviders() builds them: one Wikidata limiter
        // between them, and Wikipedia's own limiter free so only Wikidata's spacing is measured
        val http = UpstreamPools.load(POOL)
        http.givenJsonResponse("en.wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        http.givenJsonResponse("props=claims", RADIOHEAD_CLAIMS_JSON)
        val wikidataLimiter = RateLimiter(INTERVAL_MS, clock = { testScheduler.currentTime })
        val wikipedia = WikipediaProvider(http, RateLimiter(0), wikidataLimiter)
        val wikidata = WikidataProvider(http, wikidataLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44190"),
            name = "Radiohead",
        )

        // When - both providers reach Wikidata at once, one for a sitelink and one for claims
        listOf(
            async { wikipedia.enrich(request, EnrichmentType.ARTIST_BIO) },
            async { wikidata.enrich(request, EnrichmentType.ARTIST_PHOTO) },
        ).awaitAll()

        // Then - the two requests cost two intervals, not one: they waited on each other. Two
        // limiters would run both waits concurrently and finish a whole interval earlier.
        assertEquals(2 * INTERVAL_MS, testScheduler.currentTime)
        assertEquals(2, http.requestedUrls.count { it.contains("wikidata.org") })
    }

    private companion object {
        const val POOL = "wikidata-enwiki-sitelink"

        /** Long enough that one interval cannot be confused with two by rounding. */
        const val INTERVAL_MS = 1_000L

        val RADIOHEAD_EXTRACT_JSON = """
            {
              "query": {
                "pages": [
                  {"pageid": 38252, "ns": 0, "title": "Radiohead", "extract": "Radiohead are an English rock band."}
                ]
              }
            }
        """.trimIndent()

        val RADIOHEAD_CLAIMS_JSON = """
            {
              "entities": {
                "Q44190": {
                  "claims": {
                    "P18": [
                      {
                        "rank": "normal",
                        "mainsnak": {"datavalue": {"value": "Radiohead.jpg", "type": "string"}}
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
    }
}
