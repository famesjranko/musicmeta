package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

class PreviewEndpointTest {
    private val type = EnrichmentType.TRACK_PREVIEW

    private fun preview(
        result: EnrichmentResult,
        fallback: EnrichmentResult? = null,
        publicMode: Boolean = false,
        deezerRefusedAtStartup: Boolean = false,
    ): HttpResponse<String> {
        val provider = object : EnrichmentProvider {
            override val id = "deezer"
            override val displayName = "Deezer"
            override val capabilities = listOf(ProviderCapability(type, 100))
            override val requiresApiKey = false
            override val isAvailable = true
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType) = result
        }
        val builder = EnrichmentEngine.Builder()
            .config(EnrichmentConfig(enableIdentityResolution = false))
            .addProvider(provider)
        if (fallback != null) builder.addProvider(object : EnrichmentProvider by provider {
            override val id = "itunes"
            override val capabilities = listOf(ProviderCapability(type, 40))
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType) = fallback
        })
        val built = builder.build()
        val engine = if (publicMode) PublicPostureEngine(built) else built
        val refusingHost = if (deezerRefusedAtStartup) refusingHost() else null
        val reachability = refusingHost
            ?.let { HostReachability(targets = mapOf("deezer" to "http://127.0.0.1:${it.address.port}/")) }
            ?: HostReachability()
        val port = startServer(
            AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0,
            hostReachability = reachability,
        )
        return try {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:$port/api/preview?title=Karma+Police&artist=Radiohead"),
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } finally {
            engine.close()
            refusingHost?.stop(0)
        }
    }

    // Stands in for api.deezer.com turning this host away, so the startup check records REFUSED.
    private fun refusingHost(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        start()
    }

    private fun field(response: HttpResponse<String>, name: String): String =
        Json.parseToJsonElement(response.body()).jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `a blocked upstream preview returns bad gateway with an honest message`() {
        // Given - the provider reports that this host was refused.
        val result = EnrichmentResult.Error(
            type, "deezer", "HTTP 403: Deezer blocked access from this host", errorKind = ErrorKind.AUTH,
        )

        // When - the page requests a preview.
        val response = preview(result)

        // Then - the failure is a gateway error rather than a claim that the track has no preview.
        assertEquals(502, response.statusCode())
        assertEquals("Preview provider blocked access from this host (deezer). No fallback preview is available.",
            field(response, "error"))
    }

    @Test
    fun `a genuine missing preview returns not found`() {
        // Given - the provider searched successfully but has no preview.
        val result = EnrichmentResult.NotFound(type, "deezer")

        // When - the page requests a preview.
        val response = preview(result)

        // Then - only genuine absence returns the no-preview message.
        assertEquals(404, response.statusCode())
        assertEquals("No preview available for \"Karma Police\" by Radiohead", field(response, "error"))
    }

    @Test
    fun `a working preview returns the streaming URL`() {
        // Given - the provider returns a preview URL and its clip duration.
        val result = EnrichmentResult.Success(
            type,
            EnrichmentData.TrackPreview("https://cdns-preview.dzcdn.net/stream/clip.mp3", "deezer", 30000),
            "deezer", 0.9f,
        )

        // When - the page requests a preview.
        val response = preview(result)

        // Then - the page receives the URL and provider without a media download.
        assertEquals(200, response.statusCode())
        assertEquals("https://cdns-preview.dzcdn.net/stream/clip.mp3", field(response, "url"))
        assertEquals("30000", field(response, "durationMs"))
        assertEquals("deezer", field(response, "source"))
    }

    @Test
    fun `a network failure returns bad gateway rather than not found`() {
        // Given - the provider cannot reach upstream.
        val result = EnrichmentResult.Error(type, "deezer", "connection failed", errorKind = ErrorKind.NETWORK)

        // When - the page requests a preview.
        val response = preview(result)

        // Then - the page is told that the lookup failed.
        assertEquals(502, response.statusCode())
        assertEquals("Preview lookup failed (deezer). Try again later.", field(response, "error"))
    }

    @Test
    fun `a public deployment serves the iTunes fallback after a Deezer block`() {
        // Given - Deezer is blocked and iTunes supplies the requested preview.
        val block = EnrichmentResult.Error(type, "deezer", "HTTP 403: blocked", errorKind = ErrorKind.AUTH)
        val clip = EnrichmentResult.Success(type,
            EnrichmentData.TrackPreview("https://audio-ssl.itunes.apple.com/clip.m4a", "itunes"), "itunes", 0.8f)

        // When - the page requests the preview through the public-mode wrapper.
        val response = preview(block, clip, publicMode = true)

        // Then - the response contains only the existing streaming metadata fields.
        assertEquals(200, response.statusCode())
        assertEquals("https://audio-ssl.itunes.apple.com/clip.m4a", field(response, "url"))
        assertEquals("itunes", field(response, "source"))
        assertEquals(setOf("url", "durationMs", "source"), Json.parseToJsonElement(response.body()).jsonObject.keys)
    }

    @Test
    fun `an iTunes miss after a Deezer block reports that no fallback is available`() {
        // Given - Deezer blocks the host and iTunes has no matching preview.
        val block = EnrichmentResult.Error(type, "deezer", "HTTP 403: blocked", errorKind = ErrorKind.AUTH)
        val miss = EnrichmentResult.NotFound(type, "itunes")

        // When - the page requests the preview.
        val response = preview(block, miss)

        // Then - the failure reports the block without claiming that the track has no preview.
        assertEquals(502, response.statusCode())
        assertEquals("Preview provider blocked access from this host (deezer). No fallback preview is available.",
            field(response, "error"))
    }

    @Test
    fun `a Deezer cooldown on a host Deezer refused at startup still reports the block`() {
        // Given - Deezer refused this host at startup, its lookup is now skipped in cooldown, and iTunes has no match.
        val cooldown = EnrichmentResult.Error(
            type, "deezer", "Preview provider is in circuit-breaker cooldown; data was not looked up",
            errorKind = ErrorKind.NETWORK,
        )
        val miss = EnrichmentResult.NotFound(type, "itunes")

        // When - the page requests the preview.
        val response = preview(cooldown, miss, deezerRefusedAtStartup = true)

        // Then - the page is told that Deezer blocked this host rather than to try again later.
        assertEquals(502, response.statusCode())
        assertEquals("Preview provider blocked access from this host (deezer). No fallback preview is available.",
            field(response, "error"))
    }

    @Test
    fun `two provider failures report the final upstream failure`() {
        // Given - Deezer blocks the host and iTunes also fails its lookup.
        val block = EnrichmentResult.Error(type, "deezer", "HTTP 403: blocked", errorKind = ErrorKind.AUTH)
        val failed = EnrichmentResult.Error(type, "itunes", "connection failed", errorKind = ErrorKind.NETWORK)

        // When - the page requests the preview.
        val response = preview(block, failed)

        // Then - the response reports the final failure instead of a missing preview.
        assertEquals(502, response.statusCode())
        assertEquals("Preview lookup failed (itunes). Try again later.", field(response, "error"))
    }
}
