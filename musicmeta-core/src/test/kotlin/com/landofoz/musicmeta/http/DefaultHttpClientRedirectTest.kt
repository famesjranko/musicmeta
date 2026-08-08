package com.landofoz.musicmeta.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * `fetchRedirectUrlResult` against a real socket: the status has to survive the wire, since it is
 * the whole reason the method exists — a status that arrives as an indistinguishable failure is
 * a transient the provider reports as "no artwork".
 */
class DefaultHttpClientRedirectTest {

    private lateinit var server: HttpServer

    /** Status and `Location` header for the next request. */
    private var status = 200
    private var location: String? = null

    private val client = DefaultHttpClient("musicmeta-test", maxRetries = 1)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            val body = "body"
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/front-1200"

    @Test fun `a 307 is Ok carrying the Location header`() = runTest {
        // Given - how Cover Art Archive answers an available front cover
        status = 307
        location = "https://archive.org/image/front.jpg"

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is Ok carrying the Location header value
        assertEquals("https://archive.org/image/front.jpg", (result as HttpResult.Ok).body)
        assertEquals(307, result.statusCode)
    }

    @Test fun `a 200 is Ok carrying the request URL`() = runTest {
        // Given - server responds 200, no redirect
        status = 200

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - nothing to follow, so the URL asked for is the answer
        assertEquals(url(), (result as HttpResult.Ok).body)
    }

    @Test fun `a 3xx with no Location is a ClientError, not an Ok with nothing in it`() = runTest {
        // Given - server responds 302 with no Location header
        status = 302
        location = null

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is ClientError carrying the 302 status
        assertEquals(302, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `a 404 is a ClientError — no artwork, a genuine empty result`() = runTest {
        // Given - server responds 404
        status = 404

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is ClientError carrying the 404 status
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `a 429 is RateLimited, not an indistinguishable failure`() = runTest {
        // Given - server responds 429
        status = 429

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is RateLimited
        assertTrue("expected RateLimited, got $result", result is HttpResult.RateLimited)
    }

    @Test fun `a 503 is a ServerError, not an indistinguishable failure`() = runTest {
        // Given - server responds 503
        status = 503

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is ServerError carrying the 503 status
        assertEquals(503, (result as HttpResult.ServerError).statusCode)
    }

    @Test fun `an unreachable host is a NetworkError`() = runTest {
        // Given - a port nothing is listening on
        val dead = "http://127.0.0.1:1/front-1200"

        // When - the redirect URL is fetched from that address
        val result = client.fetchRedirectUrlResult(dead)

        // Then - result is NetworkError
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
    }
}
