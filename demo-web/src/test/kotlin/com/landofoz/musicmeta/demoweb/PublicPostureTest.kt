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

    /** Wires an engine and a server exactly as `main` wires them for [posture]. */
    private fun startTestServer(providers: List<EnrichmentProvider>, posture: PublicPosture): Int {
        val built = EnrichmentEngine.Builder()
            .apply { providers.forEach { addProvider(it) } }
            .cache(InMemoryEnrichmentCache())
            .build()
        val engine = if (posture.withholdsDiscogsImages) PublicPostureEngine(built) else built
        return startServer(
            AtomicReference(engine),
            AtomicReference(CacheMode.NETWORK_FIRST),
            { engine },
            // As a public deployment runs: nothing exported, so the only thing separating the two
            // arms below is the posture's own row filter, not a key one arm happens to hold.
            ApiKeyConfig(),
            0,
            unregisteredProviderIds = posture.unregisteredProviderIds,
        )
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
        val effective = keys.underPublicPosture(PublicPosture(enabled = false))

        // Then - the configuration is handed back untouched
        assertEquals(keys, effective)
    }

    @Test fun `the posture drops the Last fm key and the ListenBrainz personal token`() {
        // Given - a configuration carrying all four credentials
        val keys = fullKeys

        // When - the posture is on
        val effective = keys.underPublicPosture(PublicPosture(enabled = true))

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
        val keys = fullKeys.underPublicPosture(PublicPosture(enabled = true))

        // When - the startup report is built against what the posture withholds
        val missing = missingCredentials(keys, withheldIds = PublicPosture(enabled = true).withheldCredentialIds)

        // Then - neither withheld credential is named, and nothing else is missing either
        assertEquals(emptyList<String>(), missing.required)
        assertEquals(emptyList<String>(), missing.optional)
    }

    @Test fun `the providers table omits Last fm entirely under the posture`() {
        // Given - a public-posture instance with no Last.fm provider registered
        val port = startTestServer(artProviders(), PublicPosture(enabled = true))

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
        val port = startTestServer(artProviders(), PublicPosture(enabled = false))

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
        val port = startTestServer(artProviders(), PublicPosture(enabled = true))

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
        val port = startTestServer(artProviders(), PublicPosture(enabled = false))

        // When - an album is enriched
        val response = get(port, "/api/enrich?kind=album&name=Hunky+Dory&artist=David+Bowie")

        // Then - the Discogs image is served as it is today
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(discogsArt))
    }

    @Test fun `an unset allow list relaxes nothing and finds no unknown token`() {
        // Given - DEMO_PUBLIC_ALLOW never exported, and exported blank
        val values = listOf(null, "", "  ", ",")

        // When - each is parsed
        val parsed = values.map { parsePublicRelaxations(it) }

        // Then - none asks for a relaxation and none is treated as a typo
        assertEquals(List(values.size) { ParsedRelaxations(emptySet(), emptyList()) }, parsed)
    }

    @Test fun `every relaxation token is recognised through whitespace and case`() {
        // Given - the four tokens written as an operator plausibly types them
        val value = " LastFM , listenbrainz,  Discogs-Images ,discogs-cache,"

        // When - the list is parsed
        val parsed = parsePublicRelaxations(value)

        // Then - all four are recognised and nothing is left over
        assertEquals(PublicRelaxation.entries.toSet(), parsed.recognised)
        assertEquals(emptyList<String>(), parsed.unknown)
    }

    @Test fun `the all token lifts every restriction at once`() {
        // Given - the shorthand an operator uses to run a public instance unrestricted
        val value = RELAX_ALL_TOKEN

        // When - the list is parsed into a posture
        val posture = PublicPosture(enabled = true, relaxations = parsePublicRelaxations(value).recognised)

        // Then - no restriction remains in force
        assertFalse(posture.withholdsLastFm)
        assertFalse(posture.withholdsListenBrainzToken)
        assertFalse(posture.withholdsDiscogsImages)
        assertFalse(posture.capsDiscogsFreshness)
    }

    @Test fun `a token naming no restriction is collected rather than dropped`() {
        // Given - one good token beside a plausible misspelling of another
        val value = "lastfm,discogs_images"

        // When - the list is parsed
        val parsed = parsePublicRelaxations(value)

        // Then - the good token stands and the typo is surfaced, not silently absorbed
        assertEquals(setOf(PublicRelaxation.LASTFM), parsed.recognised)
        assertEquals(listOf("discogs_images"), parsed.unknown)
    }

    @Test fun `the none token is an explicit safe posture, recognised and lifting nothing`() {
        // Given - an operator asking for the safe posture by name rather than an empty value
        val value = "none"

        // When - the list is parsed
        val parsed = parsePublicRelaxations(value)

        // Then - it is recognised (not an unknown token that would refuse startup) and lifts nothing
        assertTrue(parsed.recognised.isEmpty())
        assertTrue(parsed.unknown.isEmpty())
    }

    @Test fun `the refusal message names the offending tokens and every valid one`() {
        // Given - two tokens that name nothing
        val unknown = listOf("discogs_images", "lastFM!")

        // When - the message a caller prints before refusing to start is built
        val message = unknownRelaxationMessage(unknown)

        // Then - it names both, and the full set an operator can choose from
        assertTrue(message.contains("discogs_images"))
        assertTrue(message.contains("lastFM!"))
        PublicRelaxation.entries.forEach { assertTrue(it.token, message.contains(it.token)) }
        assertTrue(message.contains(RELAX_ALL_TOKEN))
    }

    @Test fun `a relaxation is inert while the posture is off`() {
        // Given - every relaxation asked for on an instance that is not public
        val posture = PublicPosture(enabled = false, relaxations = PublicRelaxation.entries.toSet())

        // When - the credential and provider decisions are read off it
        val effective = fullKeys.underPublicPosture(posture)

        // Then - nothing is restricted and nothing is withheld, exactly as with no relaxations
        assertEquals(fullKeys, effective)
        assertEquals(emptySet<String>(), posture.unregisteredProviderIds)
        assertEquals(emptySet<String>(), posture.withheldCredentialIds)
        assertFalse(posture.withholdsDiscogsImages)
        assertFalse(posture.capsDiscogsFreshness)
    }

    @Test fun `relaxing lastfm passes the key through and restores its provider row`() {
        // Given - a public posture with the Last.fm restriction lifted
        val posture = PublicPosture(enabled = true, relaxations = setOf(PublicRelaxation.LASTFM))

        // When - the credentials and the provider-table filter are read off it
        val effective = fullKeys.underPublicPosture(posture)

        // Then - the key reaches the engine and no id is held out of the table, while the
        // ListenBrainz token stays withheld
        assertEquals("lastfm-key", effective.lastFmKey)
        assertEquals(null, effective.listenBrainzToken)
        assertEquals(emptySet<String>(), posture.unregisteredProviderIds)
        assertEquals(setOf("listenbrainz"), posture.withheldCredentialIds)
    }

    @Test fun `relaxing listenbrainz passes the personal token through`() {
        // Given - a public posture with only the ListenBrainz restriction lifted
        val posture = PublicPosture(enabled = true, relaxations = setOf(PublicRelaxation.LISTENBRAINZ))

        // When - the credentials are read off it
        val effective = fullKeys.underPublicPosture(posture)

        // Then - the token reaches the engine and Last.fm stays off
        assertEquals("listenbrainz-token", effective.listenBrainzToken)
        assertEquals(null, effective.lastFmKey)
        assertEquals(setOf("lastfm"), posture.withheldCredentialIds)
    }

    @Test fun `relaxing discogs-cache leaves the freshness ceiling unwired`() {
        // Given - a public posture with only the Discogs cache restriction lifted
        val posture = PublicPosture(enabled = true, relaxations = setOf(PublicRelaxation.DISCOGS_CACHE))

        // When - the two Discogs decisions are read off it
        val caps = posture.capsDiscogsFreshness
        val images = posture.withholdsDiscogsImages

        // Then - the ceiling is off and the image rule is untouched, so one token lifts one thing
        assertFalse(caps)
        assertTrue(images)
    }

    @Test fun `the startup line names what is restricted and what was relaxed`() {
        // Given - a public posture with two of the four restrictions lifted
        val posture = PublicPosture(
            enabled = true,
            relaxations = setOf(PublicRelaxation.LASTFM, PublicRelaxation.DISCOGS_CACHE),
        )

        // When - the startup line is built
        val notice = posture.notice

        // Then - it names both halves: what still binds, and what the operator chose to lift
        assertTrue(notice.contains(PublicRelaxation.LISTENBRAINZ.restriction))
        assertTrue(notice.contains(PublicRelaxation.DISCOGS_IMAGES.restriction))
        assertFalse(notice.contains(PublicRelaxation.LASTFM.restriction))
        assertTrue(notice.contains("Relaxed by DEMO_PUBLIC_ALLOW: lastfm, discogs-cache."))
    }

    @Test fun `the startup line says so when nothing is restricted or nothing is relaxed`() {
        // Given - a fully restricted posture and a fully relaxed one
        val restricted = PublicPosture(enabled = true)
        val relaxed = PublicPosture(enabled = true, relaxations = PublicRelaxation.entries.toSet())

        // When - both startup lines are built
        val restrictedNotice = restricted.notice
        val relaxedNotice = relaxed.notice

        // Then - each half says "nothing" rather than trailing off, so neither reads as truncated
        assertTrue(restrictedNotice.contains("Relaxed by DEMO_PUBLIC_ALLOW: nothing."))
        assertTrue(relaxedNotice.contains("restricted: nothing."))
    }

    @Test fun `relaxing lastfm gives it a providers row back on a public instance`() {
        // Given - a public instance with the Last.fm restriction lifted
        val posture = PublicPosture(enabled = true, relaxations = setOf(PublicRelaxation.LASTFM))
        val port = startTestServer(artProviders(), posture)

        // When - the providers table is fetched
        val response = get(port, "/api/providers")

        // Then - Last.fm is back in the table, reporting its key state like any other provider
        assertEquals(200, response.statusCode())
        val rows = Json.parseToJsonElement(response.body()).jsonObject["providers"]!!.jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["keyStatus"] }
        assertEquals("KEY_MISSING", rows["lastfm"]?.jsonPrimitive?.content)
    }

    @Test fun `relaxing discogs-images serves the Discogs image on a public instance`() {
        // Given - a public instance with only the Discogs image restriction lifted
        val posture = PublicPosture(enabled = true, relaxations = setOf(PublicRelaxation.DISCOGS_IMAGES))
        val port = startTestServer(artProviders(), posture)

        // When - an album is enriched
        val response = get(port, "/api/enrich?kind=album&name=Hunky+Dory&artist=David+Bowie")

        // Then - the Discogs image is served, so the relaxation reaches the engine wiring itself
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(discogsArt))
    }
}
