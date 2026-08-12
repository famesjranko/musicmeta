package com.landofoz.musicmeta.http

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The ladder's public contract, driven directly rather than through a client: which results retry,
 * which slot the wait comes from, and what a transport failure has to look like.
 *
 * The waits are `runTest`'s virtual time. Jitter is ±25% of the base and never takes a wait under a
 * second, so the assertions are windows.
 */
class BudgetedTransientRetryTest {

    private val retry = BudgetedTransientRetry(attemptTimeoutMs = 1_000)

    @Test fun `a shed 503 waits the Retry-After the header carried`() = runTest {
        // Given - two sheds asking for forty seconds each, then a success
        val answers = mutableListOf(shed(503, "40"), shed(503, "40"), HttpResult.Ok("body").asAttempt())
        var attempts = 0

        // When - the ladder runs
        val result = retry.execute { attempts++; answers.removeFirst() }

        // Then - both waits came from the header, not from the 2s and 4s backoff a bare shed gets
        assertEquals(HttpResult.Ok("body"), result)
        assertEquals(3, attempts)
        // Two waits of forty seconds, each jittered by up to a quarter either way; the backoff the
        // header displaces would have been 2s then 4s, nowhere near this window.
        assertTrue("slept ${testScheduler.currentTime}ms", testScheduler.currentTime in 60_000L..100_000L)
    }

    @Test fun `the Retry-After slot is read for a 5xx and ignored for anything else`() = runTest {
        // Given - a 429 whose own wait is absent, alongside a Retry-After only a 5xx may claim
        val answers = mutableListOf(
            HttpResult.RateLimited(null).asAttempt(retryAfterHeader = "60"),
            HttpResult.Ok("body").asAttempt(),
        )

        // When - the ladder runs
        val result = retry.execute { answers.removeFirst() }

        // Then - the minute was ignored and the 2s backoff taken, so a misfiled header cannot stall a call
        assertEquals(HttpResult.Ok("body"), result)
        assertTrue("slept ${testScheduler.currentTime}ms", testScheduler.currentTime in 1_500L..2_500L)
    }

    @Test fun `a thrown IOException is a transport failure and is retried`() = runTest {
        // Given - the first attempt drops the connection, the second answers
        var attempts = 0

        // When - the ladder runs
        val result = retry.execute {
            if (attempts++ == 0) throw IOException("connection reset")
            HttpResult.Ok("body").asAttempt()
        }

        // Then - the retry ran and the failure never reached the caller
        assertEquals(HttpResult.Ok("body"), result)
        assertEquals(2, attempts)
    }

    @Test fun `a returned NetworkError is a parse failure and is not retried`() = runTest {
        // Given - a body that arrived and would not parse, which a second request reproduces
        var attempts = 0

        // When - the ladder runs
        val result = retry.execute {
            attempts++
            HttpResult.NetworkError("JSON parse error: nope").asAttempt()
        }

        // Then - it surfaces on the first attempt rather than tripling the traffic
        assertEquals("JSON parse error: nope", (result as HttpResult.NetworkError).message)
        assertEquals(1, attempts)
    }

    @Test fun `a wait that does not fit the enrich budget is refused`() = runTest {
        // Given - a shed 503 asking for a minute, and 50ms of budget left
        var attempts = 0

        // When - the ladder runs inside that budget
        val result = withRetryBudgetForTest(budgetMs = 50) {
            retry.execute { attempts++; shed(503, "60") }
        }

        // Then - the shed response surfaces unretried, unslept
        assertEquals(503, (result as HttpResult.ServerError).statusCode)
        assertEquals(1, attempts)
        assertEquals("must not sleep", 0L, testScheduler.currentTime)
    }

    @Test fun `a transport failure is charged another attempt on top of the wait`() = runTest {
        // Given - a budget with room for the 2s backoff but not for the attempt after it
        var attempts = 0

        // When - the ladder runs a dropped connection inside 2.5s of budget
        val result = withRetryBudgetForTest(budgetMs = 2_500) {
            retry.execute<String> { attempts++; throw IOException("connection reset") }
        }

        // Then - refused, because a hang that already spent its timeout may spend it again
        assertTrue(result is HttpResult.NetworkError)
        assertEquals(1, attempts)
    }

    @Test fun `maxAttempts of one asks exactly once`() = runTest {
        // Given - a ladder configured off, and an upstream shedding every time
        var attempts = 0

        // When - the ladder runs
        val result = BudgetedTransientRetry(maxAttempts = 1).execute { attempts++; shed(503, null) }

        // Then - one request, no wait, the shed response
        assertEquals(503, (result as HttpResult.ServerError).statusCode)
        assertEquals(1, attempts)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `a 404 is an answer and is never retried`() = runTest {
        // Given - an upstream that would answer on a second ask
        var attempts = 0

        // When - the ladder runs against a 404
        val result = retry.execute { attempts++; HttpResult.ClientError(404).asAttempt() }

        // Then - it surfaces on the first attempt
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
        assertEquals(1, attempts)
    }

    private fun shed(code: Int, retryAfterHeader: String?): HttpAttempt<String> =
        HttpResult.ServerError(code, "shed").asAttempt(retryAfterHeader)
}
