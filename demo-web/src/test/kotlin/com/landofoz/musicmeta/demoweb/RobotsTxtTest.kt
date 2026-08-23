package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * A crawler that indexes `/api/` would trigger real enrichments — the one costly path on a
 * pay-per-request public instance. `robots.txt` disallowing it keeps a well-behaved bot off that
 * path (it does not stop a deliberate script — the admission gate does that), so this pins that the
 * file is actually served, as `text/plain`, and names `/api/`.
 */
class RobotsTxtTest {

    private fun startTestServer(): Int {
        val engine = EnrichmentEngine.Builder().cache(InMemoryEnrichmentCache()).build()
        return startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0)
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `robots txt is served as plain text and disallows the api path`() {
        // Given - a started server
        val port = startTestServer()

        // When - a crawler fetches /robots.txt
        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/robots.txt")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        // Then - it is served, as text/plain, and keeps crawlers off the costly /api/ path
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
        assertTrue(response.body().contains("Disallow: /api/"))
    }
}
