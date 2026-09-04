package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [assertNotDrift] is what the live suite asserts with, and the live suite cannot be the evidence
 * for it: it is `-Dinclude.e2e=true` gated, and the two outcomes that matter cannot be summoned on
 * demand from a real upstream. So the same provider path the suite drives is driven here over
 * [FakeHttpClient], where a 429 and a missing page are both one line.
 *
 * The distinction is the whole point of the helper — a throttled request and an article that is
 * gone are the same red to a `Success`-only assertion, and only one of them is news.
 */
class LiveResultAssertionsTest {

    @Test
    fun `a throttled bio lookup is a shed, not drift`() = runTest {
        // Given - Wikipedia answering the bio route with 429 for as long as the ladder retries
        val http = FakeHttpClient()
        http.givenHttpResult("wikipedia.org", HttpResult.RateLimited(retryAfterMs = 1000))
        val result = WikipediaProvider(http, RateLimiter(0)).enrich(RADIOHEAD, EnrichmentType.ARTIST_BIO)

        // When - the assertion the live suite uses is applied to it
        val success = assertNotDrift("Radiohead biography", result)

        // Then - it passes, and hands back no Success, so the caller skips its content assertions
        assertNull("a shed carries no content to assert against", success)
    }

    @Test
    fun `a missing article is drift and fails`() = runTest {
        // Given - Wikipedia answering 200 with the page flagged missing
        val http = FakeHttpClient()
        http.givenJsonResponse("wikipedia.org", MISSING_PAGE_JSON)
        val result = WikipediaProvider(http, RateLimiter(0)).enrich(MISSING, EnrichmentType.ARTIST_BIO)

        // When - the assertion the live suite uses is applied to it
        val failure = assertThrows(AssertionError::class.java) {
            assertNotDrift("NoSuchPageZZZQQ biography", result)
        }

        // Then - it fails, naming the upstream's own answer as the reason
        assertTrue(
            "failed for the wrong reason: ${failure.message}",
            failure.message?.contains("the upstream answered that this does not exist") == true,
        )
    }

    @Test
    fun `an answered bio lookup hands back the Success to assert against`() = runTest {
        // Given - Wikipedia answering the bio route with Radiohead's lead extract
        val http = FakeHttpClient()
        http.givenJsonResponse("wikipedia.org", RADIOHEAD_EXTRACT_JSON)
        val result = WikipediaProvider(http, RateLimiter(0)).enrich(RADIOHEAD, EnrichmentType.ARTIST_BIO)

        // When - the assertion the live suite uses is applied to it
        val success = assertNotDrift("Radiohead biography", result)

        // Then - the Success comes back, so a tolerated shed has not silently disarmed the suite
        assertNotNull("an answered lookup must still reach the content assertions", success)
        assertEquals(RADIOHEAD_EXTRACT_TEXT, (success!!.data as EnrichmentData.Biography).text)
    }

    private companion object {

        val RADIOHEAD = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        val MISSING = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "NoSuchPageZZZQQ"),
            name = "NoSuchPageZZZQQ",
        )

        const val RADIOHEAD_EXTRACT_TEXT =
            "Radiohead are an English rock band formed in Abingdon, Oxfordshire, in 1985. " +
                "The band members are Thom Yorke (vocals, guitar, keyboards); the brothers " +
                "Jonny Greenwood (guitar, keyboards, other instruments) and Colin Greenwood (bass)."

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
                        "pageprops": {
                            "wikibase-shortdesc": "English rock band",
                            "wikibase_item": "Q44190"
                        }
                    }
                ]
            }
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
    }
}
