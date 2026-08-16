package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.testkit.ContractSuite
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The contract every [HttpClient] implementation owes its callers: the same upstream response
 * classifies to the same [HttpResult], whichever transport made the request. A subclass supplies a
 * [subject] built against small `maxAttempts`/`maxRetries` — this asserts classification, not the
 * retry ladder, so a single-attempt subject keeps every case here fast and free of real sleeps; the
 * retry ladder itself is `BudgetedTransientRetry`, shared by construction (`DefaultHttpClient.kt`,
 * `OkHttpEnrichmentClient.kt`) and covered by each client's own retry test file.
 *
 * The loopback server lives entirely here, not in the subclass: every case below is driven off a
 * raw socket or `com.sun.net.httpserver.HttpServer`, neither of which either implementation's own
 * test dependencies are needed for, so a subclass supplies nothing but [subject].
 */
abstract class HttpClientContract : ContractSuite<HttpClient>() {

    private lateinit var server: HttpServer
    private val pathCounter = AtomicInteger()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    /** A URL that answers every request it receives with [status], [body] and [headers]. */
    private fun urlReturning(status: Int, body: String = "", headers: Map<String, String> = emptyMap()): String {
        val path = "/${pathCounter.incrementAndGet()}"
        server.createContext(path) { exchange ->
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return "http://127.0.0.1:${server.address.port}$path"
    }

    /** A URL that sleeps [delayMs] before answering 200 with an empty JSON object. */
    private fun urlDelayed(delayMs: Long): String {
        val path = "/${pathCounter.incrementAndGet()}"
        server.createContext(path) { exchange ->
            Thread.sleep(delayMs)
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return "http://127.0.0.1:${server.address.port}$path"
    }

    /**
     * A URL whose connection dies mid-body: a raw socket that promises a `Content-Length` of 1000
     * and delivers 7 bytes before closing, so a client reading the fixed-length body meets a
     * transport failure rather than a short but well-formed response. A separate one-shot socket,
     * not [server], because [HttpServer] does not expose writing fewer bytes than it declared.
     */
    private fun urlDroppingConnection(): String {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            socket.accept().use { connection ->
                connection.getOutputStream().use { out ->
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: application/json\r\n\r\n"
                            .toByteArray(),
                    )
                    out.write("{\"trunc".toByteArray())
                    out.flush()
                }
            }
            socket.close()
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${socket.localPort}/"
    }

    @Test
    fun `a 200 with a valid body is Ok`() = runTest {
        // Given - a server answering 200 with a parseable JSON object
        val client = subject()
        try {
            val url = urlReturning(200, """{"name":"test"}""")

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller sees the parsed body, not a failure
            assertTrue("expected Ok, got $result", result is HttpResult.Ok)
            assertEquals("test", (result as HttpResult.Ok).body.getString("name"))
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 200 with an unparseable body is NetworkError, not a thrown exception`() = runTest {
        // Given - a server answering 200 with a body that is not valid JSON
        val client = subject()
        try {
            val url = urlReturning(200, "not-json{")

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the parse failure surfaces as a classified result, not an exception out of the call
            assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 404 is a client error, not a server or network error`() = runTest {
        // Given - a server answering 404
        val client = subject()
        try {
            val url = urlReturning(404, "not found")

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller sees a client error carrying the status, distinct from a transient one
            assertTrue("expected ClientError, got $result", result is HttpResult.ClientError)
            assertEquals(404, (result as HttpResult.ClientError).statusCode)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 401 is a client error carrying the status`() = runTest {
        // Given - a server answering 401
        val client = subject()
        try {
            val url = urlReturning(401, "credentials rejected")

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller sees a client error, the same kind a 404 or a 403 produces
            assertTrue("expected ClientError, got $result", result is HttpResult.ClientError)
            assertEquals(401, (result as HttpResult.ClientError).statusCode)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 403 is a client error carrying the status`() = runTest {
        // Given - a server answering 403
        val client = subject()
        try {
            val url = urlReturning(403, "forbidden")

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller sees a client error, identical treatment to a 401
            assertTrue("expected ClientError, got $result", result is HttpResult.ClientError)
            assertEquals(403, (result as HttpResult.ClientError).statusCode)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 429 with Retry-After carries the parsed delay`() = runTest {
        // Given - a server answering 429 with a 30-second Retry-After
        val client = subject()
        try {
            val url = urlReturning(429, headers = mapOf("Retry-After" to "30"))

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller sees the delay converted to milliseconds, not left as raw seconds
            assertTrue("expected RateLimited, got $result", result is HttpResult.RateLimited)
            assertEquals(30_000L, (result as HttpResult.RateLimited).retryAfterMs)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 429 with no Retry-After is RateLimited carrying no delay`() = runTest {
        // Given - a server answering 429 with no Retry-After header
        val client = subject()
        try {
            val url = urlReturning(429)

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller still sees RateLimited, with nothing to parse into a delay
            assertTrue("expected RateLimited, got $result", result is HttpResult.RateLimited)
            assertNull((result as HttpResult.RateLimited).retryAfterMs)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a 5xx is a server error, the transient kind`() = runTest {
        // Given - a server answering 503
        val client = subject()
        try {
            val url = urlReturning(503, "upstream unavailable")

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the caller sees a server error carrying the status, not a client error
            assertTrue("expected ServerError, got $result", result is HttpResult.ServerError)
            assertEquals(503, (result as HttpResult.ServerError).statusCode)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a connection dropped mid-body is NetworkError`() = runTest {
        // Given - a connection that closes after delivering fewer bytes than its Content-Length promised
        val client = subject()
        try {
            val url = urlDroppingConnection()

            // When - the JSON result is fetched
            val result = client.fetchJsonResult(url)

            // Then - the transport failure surfaces as NetworkError, the same kind a bad parse produces
            assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        } finally {
            release(client)
        }
    }

    // SwallowedException: the catch's only job is to prove a CancellationException reached this
    // point rather than a classified HttpResult; nothing here needs the exception's own content.
    // InjectDispatcher: a real dispatcher is the assertion here, not an implementation detail to be
    // injected — on runTest's virtual one the timeout fires unconditionally and the test cannot fail.
    @Suppress("SwallowedException", "InjectDispatcher")
    @Test
    fun `a timed-out request surfaces cancellation, not a classified result`() = runTest {
        // Given - a server slower to answer than the timeout wrapping the call
        val client = subject()
        try {
            val url = urlDelayed(DELAY_MS)

            // When - the fetch is wrapped in a timeout shorter than the server's delay, on a real
            // dispatcher so the timeout runs against the wall clock — under runTest's own virtual
            // dispatcher, withTimeout fires unconditionally the instant this coroutine's blocking
            // I/O crosses onto a real thread, regardless of how fast the server actually answers.
            // The RESULT is the assertion, not the exception. withTimeout throws to its own caller
            // once the deadline elapses whatever the block did, so catching CancellationException
            // says only that a deadline passed — it is true even for a client that swallowed the
            // cancellation and returned a classified result, which is the defect this test names.
            // Only a value arriving here proves the swallow. EnrichCacheFailureTest:180-183 records
            // the same trap reached through await() instead of withTimeout.
            var classified: HttpResult<JSONObject>? = null
            var sawCancellation = false
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(TIMEOUT_MS) { classified = client.fetchJsonResult(url) }
                }
            } catch (e: CancellationException) {
                sawCancellation = true
            }

            assertNull(
                "the client swallowed the timeout's cancellation and produced a classified " +
                    "result instead of letting it propagate: $classified",
                classified,
            )

            // Then - the timeout's cancellation reached the caller; nothing along the way caught
            // it and returned a NetworkError (or any other) result in its place
            assertTrue(
                "a timeout must surface as cancellation, not a swallowed classified result",
                sawCancellation,
            )
        } finally {
            release(client)
        }
    }

    @Test
    fun `a redirect fetch resolves a 3xx to its Location header`() = runTest {
        // Given - a server answering 307 with a Location header
        val client = subject()
        try {
            val url = urlReturning(307, headers = mapOf("Location" to "https://example.test/resolved"))

            // When - the redirect URL is fetched
            val result = client.fetchRedirectUrlResult(url)

            // Then - the caller sees Ok wrapping the Location header's value, not the request URL
            assertTrue("expected Ok, got $result", result is HttpResult.Ok)
            assertEquals("https://example.test/resolved", (result as HttpResult.Ok).body)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a redirect fetch on a 200 resolves to the request URL itself`() = runTest {
        // Given - a server answering 200, so there is nothing to redirect to
        val client = subject()
        try {
            val url = urlReturning(200, "ok")

            // When - the redirect URL is fetched
            val result = client.fetchRedirectUrlResult(url)

            // Then - the caller sees Ok wrapping the URL it asked for
            assertTrue("expected Ok, got $result", result is HttpResult.Ok)
            assertEquals(url, (result as HttpResult.Ok).body)
        } finally {
            release(client)
        }
    }

    @Test
    fun `a redirect fetch to a 404 is the not-found classification, not a resolved URL`() = runTest {
        // Given - a server answering 404 instead of redirecting
        val client = subject()
        try {
            val url = urlReturning(404, "not found")

            // When - the redirect URL is fetched
            val result = client.fetchRedirectUrlResult(url)

            // Then - the caller sees a client error carrying 404, not an Ok with a resolved URL
            assertTrue("expected ClientError, got $result", result is HttpResult.ClientError)
            assertEquals(404, (result as HttpResult.ClientError).statusCode)
        } finally {
            release(client)
        }
    }

    private companion object {
        /** Longer than [TIMEOUT_MS], so the request is still in flight when the timeout fires. */
        const val DELAY_MS = 2_000L

        /** Short enough that the timeout below fires well before [DELAY_MS] elapses. */
        const val TIMEOUT_MS = 50L
    }
}
