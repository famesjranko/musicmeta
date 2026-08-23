package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * The static response-header set added under a public posture. Posture-gated (`securityHeaders`)
 * rather than unconditional, unlike the size caps: these change every response's bytes, and a
 * local instance must stay byte-identical (see the forbidden-state test below).
 */
class SecurityHeadersTest {

    private fun startTestServer(securityHeaders: Boolean = false): Int {
        val engine = EnrichmentEngine.Builder()
            .cache(InMemoryEnrichmentCache())
            .build()
        return startServer(
            AtomicReference(engine),
            AtomicReference(CacheMode.NETWORK_FIRST),
            { engine },
            ApiKeyConfig(),
            0,
            securityHeaders = securityHeaders,
        )
    }

    private val http = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test fun `a public server sends the security headers on a static response`() {
        // Given - a server started with securityHeaders enabled, as a public posture sets it
        val port = startTestServer(securityHeaders = true)

        // When - fetching the static page
        val response = get(port, "/")

        // Then - every minimal security header is present with its expected value
        val headers = response.headers()
        assertEquals("nosniff", headers.firstValue("X-Content-Type-Options").orElse(null))
        assertEquals("DENY", headers.firstValue("X-Frame-Options").orElse(null))
        assertTrue(headers.firstValue("Referrer-Policy").isPresent)
        assertTrue(headers.firstValue("Content-Security-Policy").isPresent)
        assertTrue(headers.firstValue("Permissions-Policy").isPresent)
    }

    @Test fun `the CSP permits a cross-origin preview stream`() {
        // Given - a server started with securityHeaders enabled, as a public posture sets it
        val port = startTestServer(securityHeaders = true)

        // When - fetching the static page and reading its policy
        val csp = get(port, "/").headers().firstValue("Content-Security-Policy").orElse("")

        // Then - media-src is stated, so a preview URL on a provider's CDN is not blocked by the
        // default-src 'self' fallback that every unstated directive inherits
        assertTrue("CSP states no media-src: $csp", csp.contains("media-src https:"))
    }

    @Test fun `a public server sends the security headers on an SSE response`() {
        // Given - a server started with securityHeaders enabled
        val port = startTestServer(securityHeaders = true)

        // When - opening the SSE stream endpoint, which sets its headers directly and bypasses respond()
        val response = get(port, "/api/enrich-stream?kind=artist&name=Radiohead")

        // Then - the security headers are present too, proving the SSE path is covered, not just respond()
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null))
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null))
    }

    @Test fun `a non-public server sends no security headers`() {
        // Given - a server started the way every existing 5-arg test call site builds one, with
        // securityHeaders left at its default
        val port = startTestServer()

        // When - fetching the static page
        val response = get(port, "/")

        // Then - none of the headers are present: local stays byte-identical to today
        assertNull(response.headers().firstValue("X-Content-Type-Options").orElse(null))
        assertNull(response.headers().firstValue("X-Frame-Options").orElse(null))
        assertNull(response.headers().firstValue("Content-Security-Policy").orElse(null))
    }
}
