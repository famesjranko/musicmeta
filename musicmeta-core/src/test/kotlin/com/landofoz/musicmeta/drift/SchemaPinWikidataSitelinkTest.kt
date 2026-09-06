package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.wikidata.WikidataApi
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Wikidata sitelink pin, checked against a real capture of the route it watches.
 *
 * The route serves `ARTIST_BIO`, `ARTIST_PHOTO` and `ALBUM_DESCRIPTION` — every Wikipedia answer for
 * a request that arrives without a `wikipediaTitle` — and it is the newest thing the daily watch
 * covers, so the two things a green daily run can never show are here: that the pinned paths are
 * really where the pin says on a live payload, and that the pin goes red when one of them leaves.
 *
 * It pins two levels of one chain deliberately. An empty `sitelinks` object is a healthy answer
 * about an artist with no English article, so the pin is aimed at Radiohead, where the object is
 * populated and its absence can only mean the route moved.
 */
class SchemaPinWikidataSitelinkTest {

    private val pinned = WikidataApi.SCHEMA_PIN_TARGETS.single { it.route == "enwiki sitelink" }

    private suspend fun probeWith(body: String): PinVerdict {
        val http = FakeHttpClient()
        http.givenJsonResponse(pinned.url, body)
        return probe(http, pinned)
    }

    private fun capture() = JSONObject(UpstreamPools.body(POOL, "wikidata-sitelink-radiohead.json"))

    @Test
    fun `the sitelink pin holds against the answer Wikidata really sent`() = runTest {
        // Given - the live capture of the entity the pin is aimed at
        val captured = UpstreamPools.body(POOL, "wikidata-sitelink-radiohead.json")

        // When - the pin probes a route answering with that capture
        val verdict = probeWith(captured)

        // Then - both pinned paths resolved
        assertEquals(PinVerdict.Ok, verdict)
    }

    @Test
    fun `an answer that has shed the sitelinks object is drift, naming both paths`() = runTest {
        // Given - the same capture with `sitelinks` removed, which is the shape change the parse
        // reports as EnwikiSitelink.UnreadableShape rather than as an artist with no article
        val body = capture()
        body.getJSONObject("entities").getJSONObject("Q44190").remove("sitelinks")

        // When - the pin probes a route answering with the mutated capture
        val verdict = probeWith(body.toString())

        // Then - drift, and it names the object as well as the title inside it
        assertEquals(
            PinVerdict.Drift(listOf("entities.Q44190.sitelinks", "entities.Q44190.sitelinks.enwiki.title")),
            verdict,
        )
    }

    @Test
    fun `an answer that has renamed the title field is drift, naming only that path`() = runTest {
        // Given - the same capture with `title` renamed inside a `sitelinks` that is still there
        val body = capture()
        val enwiki = body.getJSONObject("entities").getJSONObject("Q44190")
            .getJSONObject("sitelinks").getJSONObject("enwiki")
        enwiki.put("pageTitle", enwiki.remove("title"))

        // When - the pin probes a route answering with the mutated capture
        val verdict = probeWith(body.toString())

        // Then - the report separates a field that moved from the object that carries it
        assertEquals(PinVerdict.Drift(listOf("entities.Q44190.sitelinks.enwiki.title")), verdict)
    }

    @Test
    fun `the pin names the URL the api client requests for the same entity`() = runTest {
        // Given - an api client over a recording http client
        val http = FakeHttpClient()
        http.givenJsonResponse("props=sitelinks", UpstreamPools.body(POOL, "wikidata-sitelink-radiohead.json"))
        val api = WikidataApi(http, RateLimiter(0))

        // When - the route is called with the entity its pin was built from
        api.getEnwikiSitelink("Q44190")

        // Then - the api asked for exactly the document the pin asserts against
        assertEquals(listOf(pinned.url), http.requestedUrls)
    }

    private companion object {
        const val POOL = "wikidata-enwiki-sitelink"
    }
}
