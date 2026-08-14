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
 * `/api/invalidate` must clear the same key family the reload that follows it will read. A track
 * request carrying a Deezer id caches under the provider-id key (`EntityKey.kt`); an invalidation
 * that omits [InvalidateRequest.identifiers] only clears the bare-name key, so the provider-id
 * entry survives "Clear cached result & reload" — the toolbar's own claim about what it does.
 */
class InvalidateIdentifierTest {

    private class CountingDeezerPreviewProvider : EnrichmentProvider {
        val calls = AtomicInteger(0)
        override val id = "deezer"
        override val displayName = "Deezer"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            calls.incrementAndGet()
            return EnrichmentResult.Success(
                type = EnrichmentType.TRACK_PREVIEW,
                data = EnrichmentData.TrackPreview("https://example.com/preview.mp3", 30_000, id),
                provider = id,
                confidence = 0.9f,
            )
        }
    }

    private fun startTestServer(provider: CountingDeezerPreviewProvider): Int {
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

    private fun post(port: Int, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun deezerIdsParam(): String {
        val wire = WireIdentifiers(entityKind = WireEntityKind.TRACK, deezerTrackId = "107471926")
        return URLEncoder.encode(json.encodeToString(WireIdentifiers.serializer(), wire), "UTF-8")
    }

    private fun deezerIdsJson(): String =
        json.encodeToString(
            WireIdentifiers.serializer(),
            WireIdentifiers(entityKind = WireEntityKind.TRACK, deezerTrackId = "107471926"),
        )

    @Test fun `invalidating with identifiers clears the provider-id-keyed preview entry`() {
        // Given - a track preview cached under its Deezer-id-keyed entry
        val provider = CountingDeezerPreviewProvider()
        val port = startTestServer(provider)
        val idsParam = deezerIdsParam()
        val first = get(port, "/api/preview?title=Starman&artist=David+Bowie&ids=$idsParam")
        assertEquals(200, first.statusCode())
        assertEquals(1, provider.calls.get())

        // When - invalidating with the same identifiers, then requesting the preview again
        val invalidateBody = """{"kind":"track","name":"Starman","artist":"David Bowie","identifiers":${deezerIdsJson()}}"""
        val invalidated = post(port, "/api/invalidate", invalidateBody)
        assertEquals(200, invalidated.statusCode())
        val second = get(port, "/api/preview?title=Starman&artist=David+Bowie&ids=$idsParam")
        assertEquals(200, second.statusCode())

        // Then - the provider was asked again rather than serving the entry invalidation should
        // have cleared
        assertEquals(2, provider.calls.get())
    }

    @Test fun `invalidating without identifiers on an identifier-bearing row leaves the entry cached`() {
        // Given - the same provider-id-keyed preview entry
        val provider = CountingDeezerPreviewProvider()
        val port = startTestServer(provider)
        val idsParam = deezerIdsParam()
        get(port, "/api/preview?title=Starman&artist=David+Bowie&ids=$idsParam")
        assertEquals(1, provider.calls.get())

        // When - invalidating with no identifiers, then re-requesting the same
        // identifier-bearing preview
        post(port, "/api/invalidate", """{"kind":"track","name":"Starman","artist":"David Bowie"}""")
        get(port, "/api/preview?title=Starman&artist=David+Bowie&ids=$idsParam")

        // Then - the provider-id-keyed entry was never touched, so the provider is not re-asked
        assertEquals(1, provider.calls.get())
    }
}
