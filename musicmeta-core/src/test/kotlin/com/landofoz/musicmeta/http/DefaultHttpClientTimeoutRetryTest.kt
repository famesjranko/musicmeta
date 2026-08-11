package com.landofoz.musicmeta.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A read timeout is retried like a shed 5xx, on a stricter budget test: it carries no status code,
 * so the ladder used to hand it back on the first attempt after spending the whole `timeoutMs` of
 * the enclosing [EnrichDeadline].
 *
 * The strictness is the point. A shed 503 fails in milliseconds, so the ladder only has to fit the
 * sleep; a timeout has already spent `timeoutMs` and another attempt may spend it again, so the
 * budget has to cover both. These tests therefore run against real elapsed time — [EnrichDeadline]
 * reads `System.nanoTime()`, which `runTest`'s virtual clock does not move — and the server hangs
 * for real. `timeoutMs` is small here for that reason, and the deadlines are sized to leave the two
 * predicates several hundred milliseconds apart.
 */
class DefaultHttpClientTimeoutRetryTest {

    private lateinit var server: HttpServer

    /**
     * Whether the handler hangs, per request, in order; anything past the end answers at once.
     * Concurrent because a hung handler holds its own thread while the retry's is served.
     */
    private val scripted = ConcurrentLinkedQueue<Boolean>()

    /** Atomic because a hung handler still holds its thread while the retry's request arrives. */
    private val requests = AtomicInteger()

    /** Releases every hung handler at teardown, so a test's hang never outlives it. */
    private val released = CountDownLatch(1)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A pool, not the default single thread: a hung handler must not delay the retry's request,
        // or every test here reads as "timed out twice" whatever the client did.
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            if (scripted.poll() == true) released.await(10, TimeUnit.SECONDS)
            val body =
                if (exchange.requestURI.path.endsWith("array")) """[{"ok":true}]""" else """{"ok":true}"""
            // The client that timed out is gone by now, so writing to it throws; that is the
            // hang being cleaned up, not a failure.
            runCatching {
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
        }
        server.start()
    }

    @After fun stopServer() {
        released.countDown()
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/data"

    private fun arrayUrl() = "http://127.0.0.1:${server.address.port}/data/array"

    private fun client(timeoutMs: Int) = DefaultHttpClient("musicmeta-test", timeoutMs = timeoutMs)

    @Test fun `a read timeout is retried and the retry's success is returned`() = runTest {
        // Given - one request that hangs past the read timeout, then a normal response
        scripted += true

        // When - the JSON result is fetched
        val result = client(timeoutMs = 200).fetchJsonResult(url())

        // Then - the caller never sees the timeout
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests.get())
    }

    @Test fun `a read timeout is retried whichever method the caller reached for`() = runTest {
        // Given - each of the five request methods, since each maps its own IOException catch and
        // one left out would spend the timeout and hand the failure back
        val client = client(timeoutMs = 200)
        val methods: Map<String, suspend () -> HttpResult<*>> = mapOf(
            "fetchRedirectUrlResult" to { client.fetchRedirectUrlResult(url()) },
            "fetchJsonResult" to { client.fetchJsonResult(url()) },
            "fetchJsonArrayResult" to { client.fetchJsonArrayResult(arrayUrl()) },
            "postJsonResult" to { client.postJsonResult(url(), "{}") },
            "postJsonArrayResult" to { client.postJsonArrayResult(arrayUrl(), "{}") },
        )

        // When - each is driven through one hung request
        val outcomes = methods.map { (name, call) ->
            requests.set(0)
            scripted += true
            Triple(name, call(), requests.get())
        }

        // Then - none of them hands the timeout back, and each took one retry to answer
        outcomes.forEach { (name, result, attempts) ->
            assertTrue("$name: expected Ok, got $result", result is HttpResult.Ok)
            assertEquals("$name: expected one retry", 2, attempts)
        }
    }

    @Test fun `a read timeout is not retried when the budget will not cover another attempt`() = runTest {
        // Given - a 5s deadline against a 2s timeout. The first attempt leaves ~3s, which covers
        // the 2s backoff on its own — so the ladder's own test would retry — but not the backoff
        // plus the 2s the next attempt spends before it can fail the same way.
        scripted += true

        // When - the JSON result is fetched under that deadline
        val result = withContext(EnrichDeadline(budgetMs = 5_000)) {
            client(timeoutMs = 2_000).fetchJsonResult(url())
        }

        // Then - handed back unretried, rather than run into a deadline it cannot beat
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertEquals("must not retry into a certain timeout", 1, requests.get())
    }

    @Test fun `a read timeout is retried when the budget covers another attempt`() = runTest {
        // Given - the same 2s timeout against a 20s deadline, which covers backoff and attempt
        // both. This is the pair that distinguishes the budget test from a blanket refusal: only
        // the deadline differs from the test above.
        scripted += true

        // When - the JSON result is fetched under that deadline
        val result = withContext(EnrichDeadline(budgetMs = 20_000)) {
            client(timeoutMs = 2_000).fetchJsonResult(url())
        }

        // Then - the retry ran and answered
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests.get())
    }

    @Test fun `a request that keeps timing out is still a transient failure`() = runTest {
        // Given - every attempt hangs past the read timeout
        repeat(3) { scripted += true }

        // When - the exhausted result is unwrapped the way a provider unwraps it
        val result = client(timeoutMs = 200).fetchJsonResult(url())
        val thrown = runCatching { result.bodyOrThrowTransient() }.exceptionOrNull()

        // Then - three attempts, and an IOException, which mapError turns into the
        // ErrorKind.NETWORK the breaker counts as a failure rather than the null that reads as
        // NotFound. Retrying must not launder a hanging provider into a healthy-looking one.
        assertEquals(3, requests.get())
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("expected IOException, got $thrown", thrown is IOException)
    }

    @Test fun `a body that is not JSON is returned on the first attempt`() = runTest {
        // Given - a 200 whose body does not parse. It reaches the caller as NetworkError, the same
        // type a timeout does, but the transport worked: a second identical request buys a second
        // identical unparseable body, and this is the shape a provider changing its JSON arrives in.
        val html = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var htmlRequests = 0
        html.createContext("/") { exchange ->
            htmlRequests++
            val body = "<html>not json</html>"
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        html.start()

        // When - the JSON result is fetched
        val result = try {
            client(timeoutMs = 200).fetchJsonResult("http://127.0.0.1:${html.address.port}/data")
        } finally {
            html.stop(0)
        }

        // Then - handed straight back, unretried
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue(
            "expected a parse error, got $result",
            (result as HttpResult.NetworkError).message.startsWith("JSON parse error"),
        )
        assertEquals("a body that will not parse must not be retried", 1, htmlRequests)
    }
}
