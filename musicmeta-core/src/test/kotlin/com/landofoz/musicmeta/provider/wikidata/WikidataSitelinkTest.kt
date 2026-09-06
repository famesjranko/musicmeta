package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The four outcomes `wbgetentities&props=sitelinks&sitefilter=enwiki` can be read to, three of them
 * against a live capture of the route answering that way.
 *
 * The pair that earns the type is [EnwikiSitelink.NoArticle] and [EnwikiSitelink.UnreadableShape]:
 * one `optJSONObject` chain returns the same nothing for both, so Wikidata dropping the `sitelinks`
 * object would read as an artist with no English article — a silent blank on `ARTIST_BIO` and
 * `ARTIST_PHOTO` that nothing in the pipeline could tell from the truth.
 *
 * A fourth capture carries an id Wikidata merged away. It resolves to [EnwikiSitelink.Title] like
 * any other, and pins why: the answer is keyed under the id that was *asked for*, so the parse needs
 * no redirect handling and must not grow any.
 */
class WikidataSitelinkTest {

    private fun api(http: FakeHttpClient) = WikidataApi(http, RateLimiter(0))

    private fun pool() = api(UpstreamPools.load(POOL))

    @Test
    fun `an entity with an English article resolves the title Wikidata sent`() = runTest {
        // Given - Wikidata's own answer for Radiohead, the entity the schema pin is aimed at
        val api = pool()

        // When - the sitelink route is called for it
        val outcome = api.getEnwikiSitelink("Q44190")

        // Then - the enwiki title, read out of the sitelinks object
        assertEquals(EnwikiSitelink.Title("Radiohead"), outcome)
    }

    @Test
    fun `an entity whose sitelinks are empty has no English article`() = runTest {
        // Given - a musician Wikidata holds with a German article and no English one
        val api = pool()

        // When - the sitelink route is called for it
        val outcome = api.getEnwikiSitelink("Q43779")

        // Then - no article, which is an answer about the artist and not about the route
        assertEquals(EnwikiSitelink.NoArticle, outcome)
    }

    @Test
    fun `an id Wikidata does not hold is no entity`() = runTest {
        // Given - an id no entity has, which comes back carrying a `missing` marker
        val api = pool()

        // When - the sitelink route is called for it
        val outcome = api.getEnwikiSitelink("Q999999999")

        // Then - no entity, read off the marker rather than off the `sitelinks` it also lacks
        assertEquals(EnwikiSitelink.NoEntity, outcome)
    }

    @Test
    fun `an id Wikidata has merged away resolves the article of the entity it became`() = runTest {
        // Given - the live answer for an id merged into Q11036, keyed under the id that was asked
        // for and carrying the target's sitelinks
        val api = pool()

        // When - the sitelink route is called with the merged-away id
        val outcome = api.getEnwikiSitelink("Q53265341")

        // Then - the target's article, resolved without a second request and without reading
        // `redirects.to`, which is not where the answer lives
        assertEquals(EnwikiSitelink.Title("The Rolling Stones"), outcome)
    }

    @Test
    fun `a 4xx is an unreadable shape, not Wikidata saying it holds no such entity`() = runTest {
        // Given - a route that sheds the request, which bodyOrThrowTransient hands back as no body.
        // This route answers an id it does not hold at 200, so a 4xx is about the request.
        val http = FakeHttpClient()
        http.givenHttpResult("props=sitelinks", HttpResult.ClientError(400, "unknown parameter"))

        // When - the sitelink route is called
        val outcome = api(http).getEnwikiSitelink("Q44190")

        // Then - the shape is unreadable, which the caller logs, rather than a claim about the id
        assertEquals(EnwikiSitelink.UnreadableShape, outcome)
        assertNotEquals(EnwikiSitelink.NoEntity, outcome)
    }

    @Test
    fun `a sitelinks object that has left the payload is told apart from no English article`() = runTest {
        // Given - the Radiohead capture with `sitelinks` removed, as a renamed field would arrive,
        // and the live capture of an entity that genuinely has no English article
        val mutated = JSONObject(UpstreamPools.body(POOL, "wikidata-sitelink-radiohead.json"))
        mutated.getJSONObject("entities").getJSONObject("Q44190").remove("sitelinks")
        val http = UpstreamPools.load(POOL)
        http.givenJsonResponse("ids=Q44190&props=sitelinks", mutated.toString())

        // When - the route is called once for each
        val drifted = api(http).getEnwikiSitelink("Q44190")
        val noArticle = pool().getEnwikiSitelink("Q43779")

        // Then - the two answers differ, and the drifted one names the shape rather than the artist
        assertEquals(EnwikiSitelink.UnreadableShape, drifted)
        assertNotEquals(noArticle, drifted)
    }

    private companion object {
        const val POOL = "wikidata-enwiki-sitelink"
    }
}
