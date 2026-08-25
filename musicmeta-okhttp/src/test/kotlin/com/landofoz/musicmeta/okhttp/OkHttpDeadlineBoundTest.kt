package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.MusicmetaTestApi
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.withRetryBudgetForTest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The enclosing enrich() deadline binds the OkHttp call itself, not just the ladder's sleeps:
 * cancellation cannot interrupt a thread blocked in `execute()`, so the deadline only holds if the
 * call's own ceiling is clamped to what is left of it. Real sockets and real elapsed time — the
 * deadline reads `System.nanoTime()`, which `runTest`'s virtual clock does not move.
 */
@OptIn(MusicmetaTestApi::class)
class OkHttpDeadlineBoundTest {

    private val server = MockWebServer()

    @After fun tearDown() { server.shutdown() }

    private fun url(): String = server.url("/test").toString()

    @Test fun `a call's ceiling is clamped to what is left of the deadline`() = runTest {
        // Given - an upstream that accepts and never answers, and a client whose own read timeout
        // dwarfs the deadline, with no callTimeout of its own
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpEnrichmentClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            "TestAgent/1.0",
            maxAttempts = 1,
        )

        // When - one fetch runs under a 600 ms deadline, against real elapsed time
        val startNanos = System.nanoTime()
        val result = withRetryBudgetForTest(budgetMs = 600) {
            client.fetchJsonResult(url())
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the call gave up near the deadline, not at its own 5 s read timeout
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("took ${wallMs}ms against a 600ms deadline", wallMs < 2_500)
    }

    @Test fun `a consumer's own tighter callTimeout is left standing`() = runTest {
        // Given - a consumer who set a 300 ms callTimeout of their own, under a roomier deadline
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpEnrichmentClient(
            OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build(),
            "TestAgent/1.0",
            maxAttempts = 1,
        )

        // When - one fetch runs under a 5 s deadline
        val startNanos = System.nanoTime()
        val result = withRetryBudgetForTest(budgetMs = 5_000) {
            client.fetchJsonResult(url())
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the tighter consumer ceiling still decides, not the roomier deadline
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("took ${wallMs}ms against a 300ms callTimeout", wallMs < 2_000)
    }

    @Test fun `with no deadline installed the client's own timeouts stand`() = runTest {
        // Given - the same never-answering upstream, no deadline in context
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpEnrichmentClient(
            OkHttpClient.Builder().readTimeout(400, TimeUnit.MILLISECONDS).build(),
            "TestAgent/1.0",
            maxAttempts = 1,
        )

        // When - one fetch runs bare, as a consumer driving the client directly does
        val result = client.fetchJsonResult(url())

        // Then - the configured read timeout still bounds the call on its own
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
    }
}
