package com.landofoz.musicmeta.provider.wikidata

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
 * [WikidataProvider] memoizes `getEntityProperties` for the life of one
 * [com.landofoz.musicmeta.engine.ProviderCallScope]: a fan-out over ARTIST_PHOTO, COUNTRY and
 * ARTIST_LINKS fetches the same entity's claims once for the call, not once per type. Unlike the
 * other four sites in this effort, this provider had no prior regression pin -- added here per the
 * effort's rule that a child adding a memo to a previously-uncovered site adds the pin first.
 */
class WikidataMemoTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikidataProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikidataProvider(httpClient, RateLimiter(0L))
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    @Test
    fun `one artist's fanout fetches Wikidata entity properties once, not once per type`() = runTest {
        // Given - an entity whose claims answer all three types
        httpClient.givenJsonResponse("wikidata.org", ENTITY_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When - all three types are enriched in one call
        engine().enrich(
            request,
            setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.COUNTRY, EnrichmentType.ARTIST_LINKS),
        )

        // Then - one request served the whole call, not one per type
        httpClient.assertNoUrlRequestedTwice()
    }

    @Test
    fun `an unrecognised entity's properties are fetched once, not once per type`() = runTest {
        // Given - a wikidataId the entity endpoint has nothing for (unstubbed resolves to a 404)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q0"),
            name = "Unknown Artist",
        )

        // When - two types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.COUNTRY))

        // Then - the miss was looked up once, not once per type
        assertEquals(1, httpClient.countMatching("wikidata.org"))
    }

    @Test
    fun `forceRefresh re-fetches entity properties, not the previous call's memo`() = runTest {
        // Given - an entity whose properties are already fetched once
        httpClient.givenJsonResponse("wikidata.org", ENTITY_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )
        val eng = engine()
        eng.enrich(request, setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.COUNTRY))

        // When - the same engine is asked again with forceRefresh, bypassing its cache
        eng.enrich(request, setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.COUNTRY), forceRefresh = true)

        // Then - the memo did not survive into the second call: one request per call, two total
        assertEquals(2, httpClient.countMatching("wikidata.org"))
    }

    private companion object {
        // synthetic: claims shape matches WikidataProviderTest's fixtures (P18 image, P569 birth
        // date, P856 official website), extended so all three memoized types resolve
        val ENTITY_JSON = """
            {"entities":{"Q44802":{"claims":{
              "P18":[{"mainsnak":{"datavalue":{"value":"Radiohead 2016.jpg","type":"string"}}}],
              "P569":[{"mainsnak":{"datavalue":{"value":{"time":"+1968-10-07T00:00:00Z"},"type":"time"}}}],
              "P856":[{"mainsnak":{"datavalue":{"value":"https://www.radiohead.com","type":"string"}}}]
            }}}}
        """.trimIndent()
    }
}
