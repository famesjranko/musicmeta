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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `/api/health` is the page's backend-up gate and a hosting platform's probe, and it answers for
 * the process alone: the socket is bound only after every context is registered, so anything that
 * reaches this endpoint has reached a server able to serve. Nothing runs before a visitor asks,
 * which is the other half of what these pin — a start that spends an upstream call spends it
 * whether or not anyone arrives.
 */
class HealthEndpointTest {

    /** Answers no request within a test's lifetime, so anything it records was asked unprompted. */
    private class NeverAnsweringProvider : EnrichmentProvider {
        val called = CountDownLatch(1)
        override val id = "stub-genre"
        override val displayName = "Stub Genre"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            called.countDown()
            delay(TimeUnit.MINUTES.toMillis(5))
            return EnrichmentResult.Success(type, EnrichmentData.Metadata(genres = listOf("rock")), id, 0.9f)
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

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun getHealth(port: Int): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `health reports ready on the first request a started server can answer`() {
        // Given - a server whose only provider would never finish an enrichment
        val port = startTestServer(NeverAnsweringProvider())

        // When - asking for health straight away, with no request having been served yet
        val response = getHealth(port)

        // Then - 200 and ready: readiness is the process accepting requests, nothing further
        assertEquals(200, response.statusCode())
        val body = json.decodeFromString(HealthResponse.serializer(), response.body())
        assertEquals(true, body.ready)
        assertEquals("READY", body.status)
    }

    @Test
    fun `starting the server asks no provider for anything`() {
        // Given - a provider that latches the first time anything asks it to enrich
        val provider = NeverAnsweringProvider()

        // When - the server starts and is given time to do any startup work it would do
        startTestServer(provider)

        // Then - nothing was asked: a visitor's search is the only thing that spends an upstream call
        assertFalse(provider.called.await(2, TimeUnit.SECONDS))
    }
}
