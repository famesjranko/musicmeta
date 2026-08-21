package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * The public-instance posture: which credentials reach the engine, which providers the table
 * admits to, and the rule that no Discogs image leaves this process. The forbidden state is the
 * flag being unset changing anything at all, so every rule here is asserted in both directions.
 */
class PublicPostureTest {

    private val fullKeys = ApiKeyConfig(
        lastFmKey = "lastfm-key",
        fanartTvProjectKey = "fanart-key",
        discogsPersonalToken = "discogs-token",
        listenBrainzToken = "listenbrainz-token",
    )

    private val discogsArt = "https://i.discogs.com/cover.jpg"
    private val caaArt = "https://coverartarchive.org/front.jpg"

    /**
     * One ALBUM_ART image from one provider. Two of these are what produce a merged artwork
     * result: the engine's own merger ranks them by [confidence] and turns the loser into the
     * `alternatives` entry the posture has to reason about — a single provider's result carries
     * no alternatives at all.
     */
    private class ArtProvider(
        override val id: String,
        private val url: String,
        private val confidence: Float,
    ) : EnrichmentProvider {
        override val displayName = id
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.Success(
                type = EnrichmentType.ALBUM_ART,
                data = EnrichmentData.Artwork(url = url),
                provider = id,
                confidence = confidence,
            )
    }

    private fun artProviders() = listOf(
        ArtProvider(DISCOGS_ID, discogsArt, confidence = 0.9f),
        ArtProvider("coverartarchive", caaArt, confidence = 0.5f),
    )

    private fun startTestServer(providers: List<EnrichmentProvider>, publicPosture: Boolean): Int {
        val built = EnrichmentEngine.Builder()
            .apply { providers.forEach { addProvider(it) } }
            .cache(InMemoryEnrichmentCache())
            .build()
        val engine = if (publicPosture) PublicPostureEngine(built) else built
        val port = (20000..40000).random()
        startServer(
            AtomicReference(engine),
            AtomicReference(CacheMode.NETWORK_FIRST),
            { engine },
            // As a public deployment runs: nothing exported, so the only thing separating the two
            // arms below is the posture's own row filter, not a key one arm happens to hold.
            ApiKeyConfig(),
            port,
            unregisteredProviderIds = if (publicPosture) PUBLIC_UNREGISTERED_PROVIDER_IDS else emptySet(),
        )
        return port
    }

    private val http = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test fun `the flag is only set by the exact value one`() {
        // Given - the values an operator might leave in the environment
        val values = listOf(null, "", "0", "true", "yes", "1")

        // When - each is read as the public-posture flag
        val enabled = values.map { publicPostureEnabled(it) }

        // Then - only the documented "1" turns the posture on
        assertEquals(listOf(false, false, false, false, false, true), enabled)
    }

    @Test fun `with the posture off every resolved credential still reaches the engine`() {
        // Given - a configuration carrying all four credentials
        val keys = fullKeys

        // When - the posture is off
        val effective = keys.underPublicPosture(enabled = false)

        // Then - the configuration is handed back untouched
        assertEquals(keys, effective)
    }

    @Test fun `the posture drops the Last fm key and the ListenBrainz personal token`() {
        // Given - a configuration carrying all four credentials
        val keys = fullKeys

        // When - the posture is on
        val effective = keys.underPublicPosture(enabled = true)

        // Then - Last.fm and the ListenBrainz token are gone and the other two are untouched
        assertEquals(null, effective.lastFmKey)
        assertEquals(null, effective.listenBrainzToken)
        assertEquals("fanart-key", effective.fanartTvProjectKey)
        assertEquals("discogs-token", effective.discogsPersonalToken)
    }

    @Test fun `with the posture off an unset credential is still reported missing`() {
        // Given - a configuration with nothing set at all
        val keys = ApiKeyConfig()

        // When - the startup report is built with nothing withheld
        val missing = missingCredentials(keys, withheldIds = emptySet())

        // Then - every keyed provider's env var is named, Required and Optional apart
        assertEquals(listOf("LASTFM_API_KEY", "FANARTTV_API_KEY", "DISCOGS_TOKEN"), missing.required)
        assertEquals(listOf("LISTENBRAINZ_TOKEN"), missing.optional)
    }

    @Test fun `a credential the posture withheld is never reported as missing`() {
        // Given - a configuration the posture has already emptied of Last.fm and ListenBrainz
        val keys = fullKeys.underPublicPosture(enabled = true)

        // When - the startup report is built against what the posture withholds
        val missing = missingCredentials(keys, withheldIds = PUBLIC_WITHHELD_CREDENTIAL_IDS)

        // Then - neither withheld credential is named, and nothing else is missing either
        assertEquals(emptyList<String>(), missing.required)
        assertEquals(emptyList<String>(), missing.optional)
    }

    @Test fun `the providers table omits Last fm entirely under the posture`() {
        // Given - a public-posture instance with no Last.fm provider registered
        val port = startTestServer(artProviders(), publicPosture = true)

        // When - the providers table is fetched
        val response = get(port, "/api/providers")

        // Then - Last.fm has no row at all, not a row claiming its key is missing
        assertEquals(200, response.statusCode())
        val ids = Json.parseToJsonElement(response.body()).jsonObject["providers"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertFalse("lastfm" in ids)
    }

    @Test fun `the providers table keeps Last fm without the posture`() {
        // Given - the same instance with the posture off
        val port = startTestServer(artProviders(), publicPosture = false)

        // When - the providers table is fetched
        val response = get(port, "/api/providers")

        // Then - Last.fm keeps its "key missing" row, which is what makes the assertion above the
        // posture's doing rather than an artefact of the provider never registering
        assertEquals(200, response.statusCode())
        val rows = Json.parseToJsonElement(response.body()).jsonObject["providers"]!!.jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["keyStatus"] }
        assertEquals("KEY_MISSING", rows["lastfm"]?.jsonPrimitive?.content)
    }

    @Test fun `an album enrichment carries no Discogs image under the posture`() {
        // Given - a public-posture instance whose Discogs provider answers ALBUM_ART with its own image
        val port = startTestServer(artProviders(), publicPosture = true)

        // When - an album is enriched
        val response = get(port, "/api/enrich?kind=album&name=Hunky+Dory&artist=David+Bowie")

        // Then - the Discogs image appears nowhere in the payload, and the surviving alternative
        // was promoted in its place rather than the album losing its art
        assertEquals(200, response.statusCode())
        assertFalse(response.body().contains(discogsArt))
        assertTrue(response.body().contains(caaArt))
    }

    @Test fun `an album enrichment carries the Discogs image without the posture`() {
        // Given - the same provider and request with the posture off
        val port = startTestServer(artProviders(), publicPosture = false)

        // When - an album is enriched
        val response = get(port, "/api/enrich?kind=album&name=Hunky+Dory&artist=David+Bowie")

        // Then - the Discogs image is served as it is today
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(discogsArt))
    }
}
