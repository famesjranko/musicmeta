package com.landofoz.musicmeta.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * A 429 is retried whichever overload the caller reached for — the typed family used to hand it
 * straight back while `get()` retried three times.
 *
 * The retry sleeps inside the provider's held `RateLimiter` mutex, which is why it waits for a
 * per-host limiter to exist: it stops further traffic to the host that just 429'd, and costs the
 * retried request nothing against the limiter's own budget.
 */
class DefaultHttpClientRetryTest {

    private lateinit var server: HttpServer

    /** Status and `Retry-After` header per request, in order; anything past the end is a 200. */
    private val scripted = ArrayDeque<Pair<Int, String?>>()
    private var requests = 0

    private val client = DefaultHttpClient("musicmeta-test")

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            val (status, retryAfter) = scripted.removeFirstOrNull() ?: (200 to null)
            retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            val body = if (status == 200) """{"ok":true}""" else "rate limited"
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/data"

    @Test fun `a 429 is retried and the retry's success is returned`() = runTest {
        // Given — one 429 with no Retry-After, then a normal response
        scripted += 429 to null

        // When
        val result = client.fetchJsonResult(url())

        // Then — the caller never sees the 429
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests)
    }

    @Test fun `Retry-After is honoured over the default backoff`() = runTest {
        // Given — the server asks for 30s, fifteen times the 2s first backoff
        scripted += 429 to "30"

        // When
        val result = client.fetchJsonResult(url())

        // Then — it waited roughly what was asked (jitter is ±25%), not the default 2s
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertTrue("slept ${testScheduler.currentTime}ms, expected ~30s", testScheduler.currentTime >= 22_000)
    }

    @Test fun `a Retry-After past the enrich deadline returns RateLimited without sleeping`() = runTest {
        // Given — 60s asked for, against a budget of one second. Sleeping means a certain timeout,
        // and a timeout mid-fan-out loses every other provider's in-flight work.
        scripted += 429 to "60"

        // When
        val result = withContext(EnrichDeadline(budgetMs = 1_000)) { client.fetchJsonResult(url()) }

        // Then — handed back immediately, with the server's figure intact for the consumer
        assertEquals(HttpResult.RateLimited(60_000L), result)
        assertEquals("must not retry into a certain timeout", 1, requests)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `standalone, a Retry-After past MAX_RETRY_AFTER_SEC still bails out`() = runTest {
        // Given — no EnrichDeadline in context: an HttpClient driven directly by a consumer. 125s is
        // just past the 120s ceiling, which only holds because the comparison is made before jitter.
        scripted += 429 to "125"

        // When
        val result = client.fetchJsonResult(url())

        // Then — the 120s ceiling is what applies instead
        assertEquals(HttpResult.RateLimited(125_000L), result)
        assertEquals(1, requests)
    }

    @Test fun `the null-returning family gives up too when the deadline leaves no room`() = runTest {
        // Given — nothing listening, so the first attempt fails with an IOException. That path
        // retries after a backoff of its own, which the deadline has to be able to veto.
        val deadRef = "http://127.0.0.1:1/data"

        // When — a budget smaller than the 2s first backoff
        val result = withContext(EnrichDeadline(budgetMs = 1)) { client.fetchJson(deadRef) }

        // Then — null now, rather than sleeping past a deadline it cannot beat
        assertNull(result)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `a persistent 429 surfaces as RateLimited after the retries are spent`() = runTest {
        // Given — every attempt rate limited
        repeat(3) { scripted += 429 to null }

        // When
        val result = client.fetchJsonResult(url())

        // Then — RateLimited still reaches the consumer, narrowed to "still limited after retries"
        assertEquals(HttpResult.RateLimited(null), result)
        assertEquals(3, requests)
    }
}
