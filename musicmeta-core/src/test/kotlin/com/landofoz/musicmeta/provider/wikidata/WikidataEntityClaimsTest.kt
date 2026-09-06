package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two answerless outcomes `wbgetentities&props=claims` can be read to.
 *
 * They are what earns the type: one `optJSONObject` chain returned the same nothing for a 4xx and
 * for an entity Wikidata holds no claims for, so a route that stopped accepting our request read as
 * an artist with no photo, no country and no links — three capabilities blank while the provider
 * reported healthy (§31).
 *
 * A live capture of a **merged** id is here for the same reason and the mirror of it: the answer is
 * keyed under the id that was asked for, so a merged id is none of the answerless cases and the
 * parse needs no redirect handling (§40).
 */
class WikidataEntityClaimsTest {

    private class RecordingLogger : EnrichmentLogger {
        val debugs = mutableListOf<String>()
        override fun debug(tag: String, message: String) {
            debugs += "$tag: $message"
        }
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    }

    @Test
    fun `a 4xx is an unreadable shape, not Wikidata saying the entity has no claims`() = runTest {
        // Given - a route that sheds the request, which bodyOrThrowTransient hands back as no body.
        // This route answers an id it does not hold at 200, so a 4xx is about the request.
        val http = FakeHttpClient()
        http.givenHttpResult("props=claims", HttpResult.ClientError(400, "unknown parameter"))

        // When - the entity-properties route is called
        val outcome = WikidataApi(http, RateLimiter(0)).getEntityProperties("Q44190")

        // Then - the shape is unreadable, which the caller logs, rather than a claim about the id
        assertEquals(WikidataProperties.UnreadableShape, outcome)
        assertNotEquals(WikidataProperties.NoClaims, outcome)
    }

    @Test
    fun `an entity whose claims hold none of the read properties has no claims`() = runTest {
        // Given - a present entity carrying a claims object with nothing this mapper reads in it
        val http = FakeHttpClient()
        http.givenJsonResponse("props=claims", """{"entities":{"Q44190":{"id":"Q44190","claims":{}}}}""")

        // When - the entity-properties route is called for it
        val outcome = WikidataApi(http, RateLimiter(0)).getEntityProperties("Q44190")

        // Then - no claims, which is an answer about the entity and not about the route
        assertEquals(WikidataProperties.NoClaims, outcome)
    }

    @Test
    fun `an id Wikidata does not hold is no entity`() = runTest {
        // Given - the `missing` marker shape: an entity keyed under the requested id, no claims key
        val http = FakeHttpClient()
        http.givenJsonResponse("props=claims", """{"entities":{"Q999999999":{"id":"Q999999999","missing":""}}}""")

        // When - the entity-properties route is called for it
        val outcome = WikidataApi(http, RateLimiter(0)).getEntityProperties("Q999999999")

        // Then - no entity, read off the marker rather than off the claims object it also lacks
        assertEquals(WikidataProperties.NoEntity, outcome)
    }

    @Test
    fun `an id Wikidata has merged away carries the target's claims, and is not no entity`() = runTest {
        // Given - the live answer for an id merged into Q11036: keyed under the id that was asked
        // for, carrying the target's claims, naming the target only in the inner `id` field
        val http = FakeHttpClient()
        http.givenJsonResponse("props=claims", MERGED_ID_CLAIMS)

        // When - the entity-properties route is called with the merged-away id
        val outcome = WikidataApi(http, RateLimiter(0)).getEntityProperties("Q53265341")

        // Then - the target's P18, P495 and P856, which are what ARTIST_PHOTO, COUNTRY and
        // ARTIST_LINKS each read, rather than the NoEntity a stale id was thought to report
        assertNotEquals(WikidataProperties.NoEntity, outcome)
        val props = (outcome as WikidataProperties.Claims).value
        assertTrue(props.imageUrl.toString(), props.imageUrl!!.contains("Rolling_Stones_bow_post-show"))
        assertEquals("GB", props.countryOfOrigin)
        assertEquals("https://rollingstones.com", props.officialWebsite)
    }

    @Test
    fun `a top-level error at 200 is unreadable, not an entity with nothing recorded`() = runTest {
        // Given - how this route rejects a request: an `error` object at HTTP 200, which is the
        // shape the withdrawn wbgetclaims call hit on every single call
        val http = FakeHttpClient()
        http.givenJsonResponse(
            "props=claims",
            """{"error":{"code":"param-invalid","info":"The value for parameter is invalid"}}""",
        )

        // When - the entity-properties route is called
        val outcome = WikidataApi(http, RateLimiter(0)).getEntityProperties("Q44190")

        // Then - the shape is unreadable, so the request is reported rather than blanked
        assertEquals(WikidataProperties.UnreadableShape, outcome)
        assertNotEquals(WikidataProperties.NoClaims, outcome)
    }

    @Test
    fun `a claims object that has left the payload is told apart from an entity holding none`() = runTest {
        // Given - a present entity, not marked missing, with the claims object gone, as a renamed
        // field would arrive; and the live shape of an entity that genuinely records none
        val http = FakeHttpClient()
        http.givenJsonResponse("props=claims", """{"entities":{"Q44190":{"id":"Q44190","type":"item"}}}""")
        val empty = FakeHttpClient()
        empty.givenJsonResponse("props=claims", """{"entities":{"Q44190":{"id":"Q44190","claims":{}}}}""")

        // When - the route is called once for each
        val drifted = WikidataApi(http, RateLimiter(0)).getEntityProperties("Q44190")
        val noClaims = WikidataApi(empty, RateLimiter(0)).getEntityProperties("Q44190")

        // Then - the two answers differ, and the drifted one names the shape rather than the artist
        assertNotEquals(noClaims, drifted)
        assertEquals(WikidataProperties.UnreadableShape, drifted)
    }

    @Test
    fun `a 4xx on the claims route is logged as the route moving, and still answers NotFound`() = runTest {
        // Given - a provider holding a logger, against a claims route that sheds the request
        val http = FakeHttpClient()
        http.givenHttpResult("props=claims", HttpResult.ClientError(400, "unknown parameter"))
        val logger = RecordingLogger()
        val provider = WikidataProvider(http, RateLimiter(0), WikidataProvider.DEFAULT_IMAGE_SIZE, logger)

        // When - a photo is asked for an entity the route will not answer for
        val result = provider.enrich(
            EnrichmentRequest.ForArtist(identifiers = EnrichmentIdentifiers(wikidataId = "Q44190"), name = "Radiohead"),
            EnrichmentType.ARTIST_PHOTO,
        )

        // Then - the consumer still sees NotFound, and the unreadable answer is on the log
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(listOf(UNREADABLE_LOG_LINE), logger.debugs)
    }

    @Test
    fun `the engine's own default providers report an unreadable claims answer to the consumer`() = runTest {
        // Given - the engine as a consumer builds it: default providers, and a logger registered
        // after them, against a Wikidata claims route that sheds the request
        val http = FakeHttpClient()
        http.givenHttpResult("props=claims", HttpResult.ClientError(400, "unknown parameter"))
        val logger = RecordingLogger()
        val engine = EnrichmentEngine.Builder()
            .httpClient(http)
            .withDefaultProviders()
            .logger(logger)
            .build()

        // When - a photo is asked for an artist whose Wikidata id is known
        engine.enrich(
            EnrichmentRequest.ForArtist(identifiers = EnrichmentIdentifiers(wikidataId = "Q44190"), name = "Radiohead"),
            setOf(EnrichmentType.ARTIST_PHOTO),
        )

        // Then - the log the provider writes reached the consumer's logger
        assertTrue(logger.debugs.toString(), UNREADABLE_LOG_LINE in logger.debugs)
    }

    private companion object {
        private const val UNREADABLE_LOG_LINE =
            "WikidataProvider: Wikidata answered Q44190 with no readable claims body"

        // captured 2026-09-07: GET /w/api.php?action=wbgetentities&ids=Q53265341&props=claims&format=json,
        // trimmed to the read properties; each statement keeps its real mainsnak, datatype and rank.
        // Q53265341 was merged into Q11036, The Rolling Stones, and the outer key is the id that was
        // asked for while `id` names the target — the one answer where the two differ, and the
        // reason this fixture cannot be replaced by a hand-written one.
        private val MERGED_ID_CLAIMS = """
            {"entities":{"Q53265341":{"redirects":{"from":"Q53265341","to":"Q11036"},
              "type":"item","id":"Q11036","claims":{
              "P18":[{"mainsnak":{"snaktype":"value","property":"P18","datavalue":
                {"value":"Rolling Stones bow post-show 22 May 2018 in London (41437870275).jpg","type":"string"},
                "datatype":"commonsMedia"},"type":"statement","rank":"normal"}],
              "P495":[{"mainsnak":{"snaktype":"value","property":"P495","datavalue":
                {"value":{"entity-type":"item","numeric-id":145,"id":"Q145"},"type":"wikibase-entityid"},
                "datatype":"wikibase-item"},"type":"statement","rank":"normal"}],
              "P856":[{"mainsnak":{"snaktype":"value","property":"P856","datavalue":
                {"value":"https://rollingstones.com","type":"string"},
                "datatype":"url"},"type":"statement","rank":"normal"}],
              "P434":[{"mainsnak":{"snaktype":"value","property":"P434","datavalue":
                {"value":"b071f9fa-14b0-4217-8e97-eb41da73f598","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}]
            }}},"success":1}
        """.trimIndent()
    }
}
