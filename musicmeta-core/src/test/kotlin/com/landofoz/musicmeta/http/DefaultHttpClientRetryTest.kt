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

/**
 * A 429 is retried on every method — it used to be handed straight back. A 502, 503 or 504 is
 * retried on the same ladder: they are how a fronting proxy sheds load, and MusicBrainz sheds with
 * 503 rather than 429. A 500 or 501 stays terminal, so the retryable set is a closed list of three
 * codes rather than a `500..599` range test.
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
            val body = when {
                status != 200 -> "upstream said $status"
                exchange.requestURI.path.endsWith("array") -> """[{"ok":true}]"""
                else -> """{"ok":true}"""
            }
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/data"

    private fun arrayUrl() = "http://127.0.0.1:${server.address.port}/data/array"

    @Test fun `a 429 is retried and the retry's success is returned`() = runTest {
        // Given - one 429 with no Retry-After, then a normal response
        scripted += 429 to null

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - the caller never sees the 429
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests)
    }

    @Test fun `Retry-After is honoured over the default backoff`() = runTest {
        // Given - the server asks for 30s, fifteen times the 2s first backoff
        scripted += 429 to "30"

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - it waited roughly what was asked (jitter is ±25%), not the default 2s
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertTrue("slept ${testScheduler.currentTime}ms, expected ~30s", testScheduler.currentTime >= 22_000)
    }

    @Test fun `a Retry-After past the enrich deadline returns RateLimited without sleeping`() = runTest {
        // Given - 60s asked for, against a budget of one second. Sleeping means a certain timeout,
        // and a timeout mid-fan-out loses every other provider's in-flight work.
        scripted += 429 to "60"

        // When - the JSON result is fetched under a 1s enrich deadline
        val result = withContext(EnrichDeadline(budgetMs = 1_000)) { client.fetchJsonResult(url()) }

        // Then - handed back immediately, with the server's figure intact for the consumer
        assertEquals(HttpResult.RateLimited(60_000L), result)
        assertEquals("must not retry into a certain timeout", 1, requests)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `a 429 with no Retry-After gives up when the default backoff will not fit`() = runTest {
        // Given - no Retry-After header, so the wait is the exponential default (2s on the first
        // attempt) rather than a figure the server named, against a budget of one second. This is
        // the only test that exercises that arm of the backoff against a deadline: every other
        // deadline test here supplies a Retry-After, which replaces the default outright.
        scripted += 429 to null

        // When - the JSON result is fetched under a 1s enrich deadline
        val result = withContext(EnrichDeadline(budgetMs = 1_000)) { client.fetchJsonResult(url()) }

        // Then - handed back unretried, because 2s does not fit in the second that is left
        assertEquals(HttpResult.RateLimited(null), result)
        assertEquals("must not retry into a certain timeout", 1, requests)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `standalone, a Retry-After past MAX_RETRY_AFTER_SEC still bails out`() = runTest {
        // Given - no EnrichDeadline in context: an HttpClient driven directly by a consumer. 125s is
        // just past the 120s ceiling, which only holds because the comparison is made before jitter.
        scripted += 429 to "125"

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - the 120s ceiling is what applies instead
        assertEquals(HttpResult.RateLimited(125_000L), result)
        assertEquals(1, requests)
    }

    @Test fun `a persistent 429 surfaces as RateLimited after the retries are spent`() = runTest {
        // Given - every attempt rate limited
        repeat(3) { scripted += 429 to null }

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - RateLimited still reaches the consumer, narrowed to "still limited after retries"
        assertEquals(HttpResult.RateLimited(null), result)
        assertEquals(3, requests)
    }

    @Test fun `a 503 is retried and the retry's success is returned`() = runTest {
        // Given - one shed response carrying the `Retry-After: 0` MusicBrainz sends with it
        scripted += 503 to "0"

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - the caller never sees the 503, and the zero was floored to a 1s wait rather than
        // replayed immediately at an upstream that just shed
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests)
        assertEquals("Retry-After: 0 must floor at 1s", 1_000L, testScheduler.currentTime)
    }

    @Test fun `a 503 is retried whichever method the caller reached for`() = runTest {
        // Given - each of the five request methods, since the shed response's Retry-After is read
        // at each one's own mapping site and a method left out would lose it silently
        val methods: Map<String, suspend () -> HttpResult<*>> = mapOf(
            "fetchRedirectUrlResult" to { client.fetchRedirectUrlResult(url()) },
            "fetchJsonResult" to { client.fetchJsonResult(url()) },
            "fetchJsonArrayResult" to { client.fetchJsonArrayResult(arrayUrl()) },
            "postJsonResult" to { client.postJsonResult(url(), "{}") },
            "postJsonArrayResult" to { client.postJsonArrayResult(arrayUrl(), "{}") },
        )

        // When - each is driven through one shed response asking for 30s, fifteen times the 2s
        // default it falls back to when the header is not read

        // Then - none of them hands the 503 back, and each waited what the server asked
        methods.forEach { (name, call) ->
            requests = 0
            scripted += 503 to "30"
            val before = testScheduler.currentTime
            val result = call()
            assertTrue("$name: expected Ok, got $result", result is HttpResult.Ok)
            assertEquals("$name: expected one retry", 2, requests)
            val slept = testScheduler.currentTime - before
            assertTrue("$name: slept ${slept}ms, expected ~30s", slept >= 22_000)
        }
    }

    @Test fun `a 502 is retried on the same terms as a 503`() = runTest {
        // Given - one bad-gateway response, then a normal one
        scripted += 502 to null

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - the caller never sees the 502
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests)
    }

    @Test fun `a 504 is retried on the same terms as a 503`() = runTest {
        // Given - one gateway-timeout response, then a normal one
        scripted += 504 to null

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - the caller never sees the 504
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, requests)
    }

    @Test fun `a 500 is returned on the first attempt`() = runTest {
        // Given - a 500, which says something broke, not that a retry would help
        scripted += 500 to null

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - handed straight back, unretried
        assertTrue("expected ServerError, got $result", result is HttpResult.ServerError)
        assertEquals(500, (result as HttpResult.ServerError).statusCode)
        assertEquals("a 500 must not be retried", 1, requests)
    }

    @Test fun `a 501 is returned on the first attempt`() = runTest {
        // Given - a 501, which is "not implemented" and never will be
        scripted += 501 to null

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - handed straight back, unretried
        assertTrue("expected ServerError, got $result", result is HttpResult.ServerError)
        assertEquals(501, (result as HttpResult.ServerError).statusCode)
        assertEquals("a 501 must not be retried", 1, requests)
    }

    @Test fun `a 503 with no Retry-After backs off exponentially until the retries are spent`() = runTest {
        // Given - every attempt shed, and no Retry-After to replace the default backoff
        repeat(3) { scripted += 503 to null }

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - three attempts, spaced by the 2s and 4s defaults less the ±25% jitter floor
        assertEquals(HttpResult.ServerError(503, "upstream said 503"), result)
        assertEquals(3, requests)
        assertTrue("slept ${testScheduler.currentTime}ms, expected at least 4.5s", testScheduler.currentTime >= 4_500)
    }

    @Test fun `a 503 whose Retry-After runs past the enrich deadline is not retried`() = runTest {
        // Given - 60s asked for, against a budget of one second. Sleeping means a certain timeout,
        // and a timeout mid-fan-out loses every other provider's in-flight work.
        scripted += 503 to "60"

        // When - the JSON result is fetched under a 1s enrich deadline
        val result = withContext(EnrichDeadline(budgetMs = 1_000)) { client.fetchJsonResult(url()) }

        // Then - handed back immediately
        assertEquals(HttpResult.ServerError(503, "upstream said 503"), result)
        assertEquals("must not retry into a certain timeout", 1, requests)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `a 503 that survives its retries is still a transient failure`() = runTest {
        // Given - every attempt shed
        repeat(3) { scripted += 503 to null }

        // When - the exhausted result is unwrapped the way a provider unwraps it
        val result = client.fetchJsonResult(url())
        val thrown = runCatching { result.bodyOrThrowTransient() }.exceptionOrNull()

        // Then - an IOException, which mapError turns into the ErrorKind.NETWORK the breaker
        // counts as a failure, rather than the null that would read as NotFound
        assertTrue("expected IOException, got $thrown", thrown is IOException)
        assertEquals("HTTP 503: server error", thrown?.message)
    }
}
