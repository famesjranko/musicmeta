package com.landofoz.musicmeta.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Redirects are followed by [DefaultHttpClient]'s own loop, not the JDK's, so each hop's timeouts
 * can be re-clamped to the enclosing deadline — [DefaultHttpClientDeadlineBoundTest] pins why.
 * These tests pin that the loop keeps the JDK's *semantics*: which codes follow, method rewriting,
 * relative resolution, the protocol boundary, and the hop cap.
 */
class DefaultHttpClientRedirectFollowTest {

    private lateinit var server: HttpServer

    /** Method and path of every request the server saw, in order. */
    private val seen = ConcurrentLinkedQueue<Pair<String, String>>()

    private val client = DefaultHttpClient("musicmeta-test", maxRetries = 1)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange.requestMethod to exchange.requestURI.path
            val path = exchange.requestURI.path
            runCatching {
                when {
                    path.startsWith("/redirect/") -> {
                        val remaining = path.removePrefix("/redirect/").toInt()
                        val next = if (remaining <= 1) "/final" else "/redirect/${remaining - 1}"
                        exchange.responseHeaders.add("Location", next)
                        exchange.sendResponseHeaders(302, -1)
                    }
                    path == "/see-other" -> {
                        exchange.responseHeaders.add("Location", "/final")
                        exchange.sendResponseHeaders(303, -1)
                    }
                    path == "/temporary" -> {
                        exchange.responseHeaders.add("Location", "/final")
                        exchange.sendResponseHeaders(307, -1)
                    }
                    path == "/cross-protocol" -> {
                        exchange.responseHeaders.add("Location", "https://127.0.0.1:1/never")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    path == "/loop" -> {
                        exchange.responseHeaders.add("Location", "/loop")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    else -> {
                        val body = """{"ok":true}"""
                        exchange.sendResponseHeaders(200, body.length.toLong())
                        exchange.responseBody.use { it.write(body.toByteArray()) }
                    }
                }
            }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private fun base() = "http://127.0.0.1:${server.address.port}"

    @Test fun `a GET follows a relative redirect chain to the final body`() = runTest {
        // Given - a two-hop 302 chain whose Location headers are relative paths

        // When - the JSON result is fetched
        val result = client.fetchJsonResult("${base()}/redirect/2")

        // Then - the final body arrives, having walked every hop
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(
            listOf("/redirect/2", "/redirect/1", "/final"),
            seen.map { it.second },
        )
    }

    @Test fun `a GET follows a 307 keeping its method`() = runTest {
        // Given - a 307 to the final body

        // When - the JSON result is fetched
        val result = client.fetchJsonResult("${base()}/temporary")

        // Then - followed, still as GET
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(listOf("GET", "GET"), seen.map { it.first })
    }

    @Test fun `a POST 303 is followed as a GET with no body`() = runTest {
        // Given - a 303 See Other answering the POST, which the JDK's own following re-issues as
        // GET — the create-then-fetch shape 303 exists for

        // When - the POST is sent
        val result = client.postJsonResult("${base()}/see-other", """{"in":1}""")

        // Then - the follow-up hop is a GET and the final body arrives
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(listOf("POST" to "/see-other", "GET" to "/final"), seen.toList())
    }

    @Test fun `a POST 307 is not followed, since following would re-send the body`() = runTest {
        // Given - a 307 answering the POST, which keeps the method and so cannot be followed
        // without re-sending the request body — the JDK does not, and neither do we

        // When - the POST is sent
        val result = client.postJsonResult("${base()}/temporary", """{"in":1}""")

        // Then - the 307 surfaces to the caller and no second request was made
        assertTrue("expected ClientError, got $result", result is HttpResult.ClientError)
        assertEquals(307, (result as HttpResult.ClientError).statusCode)
        assertEquals(1, seen.size)
    }

    @Test fun `a redirect that changes protocol is surfaced, not followed`() = runTest {
        // Given - a 302 whose Location crosses from http to https, the boundary the JDK refuses

        // When - the JSON result is fetched
        val result = client.fetchJsonResult("${base()}/cross-protocol")

        // Then - the 302 surfaces to the caller and the https target was never contacted
        assertTrue("expected ClientError, got $result", result is HttpResult.ClientError)
        assertEquals(302, (result as HttpResult.ClientError).statusCode)
        assertEquals(1, seen.size)
    }

    @Test fun `a redirect loop is cut at the hop cap instead of spinning`() = runTest {
        // Given - a 302 that points back at itself forever

        // When - the JSON result is fetched
        val result = client.fetchJsonResult("${base()}/loop")

        // Then - a transport failure surfaces once the cap cuts the loop
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("expected the cap to allow up to 21 hops, saw ${seen.size}", seen.size in 20..22)
    }
}
