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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `MAX_QUERY_PARAM_LENGTH` on the free-text query params that ride into providers — `name`/
 * `artist`/`album` on `/api/enrich`, `q`/`artist` on `/api/search`, `title`/`artist`/`album` on
 * `/api/preview` — mirroring the existing unconditional `MAX_IDS_PARAM_LENGTH` cap on `ids`.
 */
class QueryParamLimitTest {

    private class CountingProvider : EnrichmentProvider {
        val calls = AtomicInteger(0)
        override val id = "stub-genre"
        override val displayName = "Stub Genre"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(
            ProviderCapability(EnrichmentType.GENRE, 100),
            ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100),
        )

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            calls.incrementAndGet()
            return EnrichmentResult.Success(
                type,
                if (type == EnrichmentType.TRACK_PREVIEW) {
                    EnrichmentData.TrackPreview("https://example.com/p.mp3", 30_000, id)
                } else {
                    EnrichmentData.Metadata(genres = listOf("rock"))
                },
                id,
                0.9f,
            )
        }
    }

    private fun startTestServer(provider: CountingProvider): Int {
        val engine = EnrichmentEngine.Builder()
            .addProvider(provider)
            .cache(InMemoryEnrichmentCache())
            .build()
        return startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0)
    }

    private val http = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun overLong(): String = "a".repeat(257).let { URLEncoder.encode(it, "UTF-8") }

    @Test fun `an over-long name is rejected with 400 before the engine is called`() {
        // Given - a provider that would record any call the server let through
        val provider = CountingProvider()
        val port = startTestServer(provider)

        // When - requesting /api/enrich with a name longer than the cap
        val response = get(port, "/api/enrich?kind=track&name=${overLong()}&artist=x")

        // Then - rejected with 400 and the provider was never asked
        assertEquals(400, response.statusCode())
        assertEquals(0, provider.calls.get())
    }

    @Test fun `an over-long search q is rejected with 400 before the engine is called`() {
        // Given - a provider that would record any call the server let through
        val provider = CountingProvider()
        val port = startTestServer(provider)

        // When - requesting /api/search with a q longer than the cap
        val response = get(port, "/api/search?kind=track&q=${overLong()}")

        // Then - rejected with 400 and the provider was never asked
        assertEquals(400, response.statusCode())
        assertEquals(0, provider.calls.get())
    }

    @Test fun `an over-long preview title is rejected with 400 before the engine is called`() {
        // Given - a provider that would record any call the server let through
        val provider = CountingProvider()
        val port = startTestServer(provider)

        // When - requesting /api/preview with a title longer than the cap
        val response = get(port, "/api/preview?title=${overLong()}&artist=x")

        // Then - rejected with 400 and the provider was never asked
        assertEquals(400, response.statusCode())
        assertEquals(0, provider.calls.get())
    }
}
