package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.http.BudgetedTransientRetry
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.withRetryBudgetForTest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The five behaviours core's ladder has to give the OkHttp adapter, which had no retry at all: a
 * routine shed 503 used to surface as an error on the first shed and repeated sheds took the provider
 * out of rotation.
 *
 * The sleeps are `runTest`'s virtual time, so they cost nothing here. The *budget* is not — an
 * `EnrichDeadline` reads `System.nanoTime()`, which the virtual clock does not move — so the refusal
 * test below compares a real 50ms against a wait the upstream asked a second for.
 */
class OkHttpTransientRetryTest {

    private val server = MockWebServer()
    private val client = OkHttpEnrichmentClient(OkHttpClient(), "TestAgent/1.0")

    @After fun tearDown() { server.shutdown() }

    private fun url(path: String = "/test"): String = server.url(path).toString()

    private fun okJson() = MockResponse().setResponseCode(200).setBody("""{"ok":true}""")

    @Test fun `a shed 503 carrying Retry-After is retried and the retry succeeds`() = runTest {
        // Given - the upstream sheds once with a Retry-After, then answers
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(okJson())

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - the second attempt's success is what surfaces, after the second it asked for
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, server.requestCount)
        // The header's second, plus up to a quarter of it in jitter and never under a second.
        assertTrue("slept ${testScheduler.currentTime}ms", testScheduler.currentTime in 1_000L..1_250L)
    }

    @Test fun `a wait that would overrun the enrich deadline is refused`() = runTest {
        // Given - the upstream sheds asking for a second, and 50ms of enrich budget is left
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(okJson())

        // When - the JSON result is fetched inside that budget
        val result = withRetryBudgetForTest(budgetMs = 50) { client.fetchJsonResult(url()) }

        // Then - the shed response surfaces unretried rather than certainly timing out the fan-out
        assertEquals(503, (result as HttpResult.ServerError).statusCode)
        assertEquals(1, server.requestCount)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `the same shed response is retried when the budget has room for the wait`() = runTest {
        // Given - the same fixture as the refusal above, under a budget the second fits in
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(okJson())

        // When - the JSON result is fetched inside that budget
        val result = withRetryBudgetForTest(budgetMs = 60_000) { client.fetchJsonResult(url()) }

        // Then - the retry runs, so the refusal above is the budget and not a missing ladder
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a dropped connection is retried`() = runTest {
        // Given - the first connection dies mid-body, a failure OkHttp does not recover, then it answers
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"id":1},{"id":2}]""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":1}]"""))

        // When - the JSON array result is fetched
        val result = client.fetchJsonArrayResult(url())

        // Then - the ladder retried it: the backoff it slept is time OkHttp's own recovery never takes
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, server.requestCount)
        assertTrue("must have slept", testScheduler.currentTime >= 1_000L)
    }

    @Test fun `a 404 surfaces on the first attempt and is never retried`() = runTest {
        // Given - nothing at that address, which is an answer and not a transient failure
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - it surfaces as a ClientError after exactly one request
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun `with no enrich deadline installed the retry still runs on the fallback budget`() = runTest {
        // Given - a consumer driving the client directly, and a shed 503 with no Retry-After
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        // When - a redirect URL is resolved with no deadline in context
        val result = client.fetchRedirectUrlResult(url())

        // Then - the exponential fallback wait is taken and the retry surfaces
        assertTrue("expected Ok, got $result", result is HttpResult.Ok)
        assertEquals(2, server.requestCount)
        assertTrue("must have slept", testScheduler.currentTime >= 1_000L)
    }

    @Test fun `the attempt charge comes from the client's own timeouts, never from callTimeout alone`() {
        // Given - three clients: OkHttp's defaults, a call timeout, and every timeout disabled
        val defaults = OkHttpClient()
        val withCallTimeout = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        val untimed = OkHttpClient.Builder()
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        // When - each is asked what one attempt through it may spend
        val charges = listOf(defaults, withCallTimeout, untimed).map { it.attemptCostMs() }

        // Then - connect plus read where callTimeout is the 0 it defaults to, and never a 0 charge
        assertEquals(
            listOf(
                defaults.connectTimeoutMillis + defaults.readTimeoutMillis,
                5_000,
                BudgetedTransientRetry.DEFAULT_ATTEMPT_TIMEOUT_MS,
            ),
            charges,
        )
    }
}
