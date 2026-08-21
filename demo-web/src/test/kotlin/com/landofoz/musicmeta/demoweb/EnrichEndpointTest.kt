package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP-level coverage for `/api/enrich`, the one endpoint with no coverage above the pure-function
 * layer before this class. The identifier round trip is the #210 defect at the HTTP layer: a
 * `WireIdentifiers` payload the caller already resolved (a Deezer track id from a prior lookup)
 * must reach the engine, not be silently dropped on the way in.
 */
class EnrichEndpointTest {

    /** Records every [EnrichmentRequest] it is asked to enrich, so a test can inspect what the engine handed it. */
    private class RecordingGenreProvider(
        private val result: EnrichmentResult = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = listOf(GenreTag("Alt Rock", 0.8f, sources = listOf("stub-genre")))),
            provider = "stub-genre",
            confidence = 0.8f,
        ),
    ) : EnrichmentProvider {
        val recordedRequests = ConcurrentLinkedQueue<EnrichmentRequest>()
        override val id = "stub-genre"
        override val displayName = "Stub Genre"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            recordedRequests.add(request)
            return result
        }
    }

    private fun startTestServer(provider: EnrichmentProvider): Int {
        val engine = EnrichmentEngine.Builder()
            .addProvider(provider)
            .cache(InMemoryEnrichmentCache())
            .build()
        val port = (20000..40000).random()
        startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), port)
        return port
    }

    private val http = HttpClient.newHttpClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun deezerIdsParam(): String {
        val wire = WireIdentifiers(entityKind = WireEntityKind.TRACK, deezerTrackId = "107471926")
        return URLEncoder.encode(json.encodeToString(WireIdentifiers.serializer(), wire), "UTF-8")
    }

    @Test fun `enrich for a track threads the ids query parameter into the engine request`() {
        // Given - a track enrichment provider that records the request it receives
        val provider = RecordingGenreProvider()
        val port = startTestServer(provider)

        // When - requesting /api/enrich for a track carrying a resolved Deezer id
        val response = get(port, "/api/enrich?kind=track&name=Starman&artist=David+Bowie&ids=${deezerIdsParam()}")

        // Then - the identifier reached the engine intact, not dropped on the way in
        assertEquals(200, response.statusCode())
        val recorded = provider.recordedRequests
            .filterIsInstance<EnrichmentRequest.ForTrack>()
            .single { it.title == "Starman" }
        assertEquals("107471926", recorded.identifiers.get(IdentifierNamespace.DEEZER))
    }

    @Test fun `enrich for a track carries the genre data a provider actually returned`() {
        // Given - a provider that answers GENRE with a concrete tag
        val provider = RecordingGenreProvider()
        val port = startTestServer(provider)

        // When - requesting /api/enrich for that track
        val response = get(port, "/api/enrich?kind=track&name=Starman&artist=David+Bowie")

        // Then - the wire body carries the actual genre chip this provider supplied, checked
        // against the raw JSON key rather than only the DTO the server itself encoded it with — a
        // field the server silently renamed would still round-trip through that same class
        // undetected. Genre naming is normalized downstream, so match on confidence and provider
        // id, not the raw name.
        assertEquals(200, response.statusCode())
        val genresJson = Json.parseToJsonElement(response.body())
            .jsonObject["summary"]!!.jsonObject["genres"]!!.jsonArray
        assertTrue(
            genresJson.any { chip ->
                chip.jsonObject["confidence"]?.jsonPrimitive?.float == 0.8f &&
                    chip.jsonObject["sources"]!!.jsonArray.any { it.jsonPrimitive.content == "stub-genre" }
            },
        )
    }

    @Test fun `enrich for a track whose provider found nothing is 200, not 500`() {
        // Given - a provider that answers GENRE with NotFound rather than throwing
        val provider = RecordingGenreProvider(EnrichmentResult.NotFound(EnrichmentType.GENRE, "stub-genre"))
        val port = startTestServer(provider)

        // When - requesting /api/enrich for that track
        val response = get(port, "/api/enrich?kind=track&name=Starman&artist=David+Bowie")

        // Then - the request still succeeds; an empty provider result is not a server error
        assertEquals(200, response.statusCode())
        val decoded = json.decodeFromString(DemoResponse.serializer(), response.body())
        assertTrue(decoded.summary.genres.isEmpty())
    }
}
