package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * `POST /api/config` under a public posture — the mutating half of a control every visitor sees.
 * `GET /api/config` carries no coverage here because it stays open under every posture (see
 * `Server.kt` `handleConfig`); these pin only the `POST` gate `requireMaintainerSecret` adds.
 */
class ConfigAuthTest {

    private fun startTestServer(
        requireMaintainerSecret: Boolean = false,
        maintainerSecret: String? = null,
    ): Int {
        val engine = EnrichmentEngine.Builder()
            .cache(InMemoryEnrichmentCache())
            .build()
        val port = (20000..40000).random()
        startServer(
            AtomicReference(engine),
            AtomicReference(CacheMode.NETWORK_FIRST),
            { engine },
            ApiKeyConfig(),
            port,
            requireMaintainerSecret = requireMaintainerSecret,
            maintainerSecret = maintainerSecret,
        )
        return port
    }

    private val http = HttpClient.newHttpClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postConfig(port: Int, cacheMode: String, secret: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/config"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"cacheMode":"$cacheMode"}"""))
        if (secret != null) builder.header("X-Maintainer-Secret", secret)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun `config POST without the maintainer secret is refused and does not rebuild the engine`() {
        // Given - a public-posture server requiring the maintainer secret
        val port = startTestServer(requireMaintainerSecret = true, maintainerSecret = "s3cr3t")

        // When - POSTing a cache-mode change with no secret header at all
        val response = postConfig(port, "STALE_IF_ERROR")

        // Then - the request is refused and the engine is never swapped, proven by GET still
        // reporting the original mode
        assertEquals(403, response.statusCode())
        val stillOriginal = json.decodeFromString(ConfigResponse.serializer(), get(port, "/api/config").body())
        assertEquals("NETWORK_FIRST", stillOriginal.cacheMode)
    }

    @Test fun `config POST with the correct secret rebuilds`() {
        // Given - a public-posture server requiring the maintainer secret
        val port = startTestServer(requireMaintainerSecret = true, maintainerSecret = "s3cr3t")

        // When - POSTing a cache-mode change with the matching secret header
        val response = postConfig(port, "STALE_IF_ERROR", secret = "s3cr3t")

        // Then - the change is accepted and echoed back
        assertEquals(200, response.statusCode())
        val decoded = json.decodeFromString(ConfigResponse.serializer(), response.body())
        assertEquals("STALE_IF_ERROR", decoded.cacheMode)
    }

    @Test fun `config POST with the secret unset under public posture is refused`() {
        // Given - a public-posture server that requires the secret but was never given one to check
        // against — the fail-closed case a missing DEMO_MAINTAINER_SECRET produces
        val port = startTestServer(requireMaintainerSecret = true, maintainerSecret = null)

        // When - POSTing a cache-mode change, with or without a header, there is nothing it could match
        val response = postConfig(port, "STALE_IF_ERROR", secret = "anything")

        // Then - the request is refused rather than let through by an absent check
        assertEquals(403, response.statusCode())
    }

    @Test fun `config POST on a non-public server rebuilds with no header`() {
        // Given - a server built the way every existing 5-arg test call site builds one, with no
        // maintainer gate requested
        val port = startTestServer()

        // When - POSTing a cache-mode change with no secret header
        val response = postConfig(port, "STALE_IF_ERROR")

        // Then - the change is accepted exactly as it always was: local behaviour is unchanged
        assertEquals(200, response.statusCode())
        val decoded = json.decodeFromString(ConfigResponse.serializer(), response.body())
        assertEquals("STALE_IF_ERROR", decoded.cacheMode)
    }

    @Test fun `GET config reports whether a POST needs the maintainer secret`() {
        // Given - one server that requires the secret and one that does not
        val publicPort = startTestServer(requireMaintainerSecret = true, maintainerSecret = "s3cr3t")
        val localPort = startTestServer()

        // When - reading the config each server reports
        val publicConfig = json.decodeFromString(ConfigResponse.serializer(), get(publicPort, "/api/config").body())
        val localConfig = json.decodeFromString(ConfigResponse.serializer(), get(localPort, "/api/config").body())

        // Then - only the gated server tells the page a secret is required
        assertTrue(publicConfig.requiresMaintainerSecret)
        assertEquals(false, localConfig.requiresMaintainerSecret)
    }
}
