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
    }
}
