package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP-level coverage for `/api/search`, which had none above the pure-function layer before this
 * class. `handleSearch` builds its response from `engine.search()`'s candidates directly (unlike
 * `/api/enrich`, no fallback shell) so an empty result is the case worth pinning down: it must stay
 * a 200 with an empty list, not turn into an error.
 */
class SearchEndpointTest {

    private class StubSearchProvider(private val candidates: List<SearchCandidate>) : EnrichmentProvider {
        override val id = "stub-search"
        override val displayName = "Stub Search"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.NotFound(type, id)

        override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> =
            candidates
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

    @Test fun `search for a track with a match carries the candidate's actual fields`() {
        // Given - a provider whose search returns one concrete candidate
        val candidate = SearchCandidate(
            title = "Starman",
            artist = "David Bowie",
            year = "1972",
            country = null,
            releaseType = "single",
            score = 95,
            thumbnailUrl = null,
            identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-starman"),
            provider = "stub-search",
        )
        val port = startTestServer(StubSearchProvider(listOf(candidate)))

        // When - searching for that track
        val response = get(port, "/api/search?kind=track&q=Starman&artist=David+Bowie")

        // Then - the wire body carries the candidate's own fields, checked against the raw JSON
        // key rather than only the DTO the server itself encoded it with — a field the server
        // silently renamed would still round-trip through that same class undetected.
        assertEquals(200, response.statusCode())
        val hitJson = Json.parseToJsonElement(response.body())
            .jsonObject["candidates"]!!.jsonArray
            .single().jsonObject
        assertEquals("Starman", hitJson["title"]?.jsonPrimitive?.content)
        assertEquals(95, hitJson["score"]?.jsonPrimitive?.int)
        assertEquals("mbid-starman", hitJson["mbid"]?.jsonPrimitive?.content)
    }

    @Test fun `search with no matching candidates is an empty result, not an error`() {
        // Given - a provider whose search finds nothing
        val port = startTestServer(StubSearchProvider(emptyList()))

        // When - searching for a track no provider can find
        val response = get(port, "/api/search?kind=track&q=Nonexistent&artist=Nobody")

        // Then - the response is a normal 200 with an empty candidate list, not a server error
        assertEquals(200, response.statusCode())
        val decoded = json.decodeFromString(SearchResponse.serializer(), response.body())
        assertTrue(decoded.candidates.isEmpty())
    }
}
