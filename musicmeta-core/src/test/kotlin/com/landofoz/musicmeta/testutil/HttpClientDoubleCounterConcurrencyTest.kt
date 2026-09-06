package com.landofoz.musicmeta.testutil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GatedHttpClient] and [CancellingOnceHttpClient] each answer a question with a counter, and both
 * are wrapped around clients that concurrent callers reach: the gate counts *how many callers got
 * in at once*, which is the concurrency itself, and "the first call" names one call only if the
 * counter behind it cannot lose an increment.
 *
 * Both claims are counts, never durations (`docs/pitfalls.md` §38). `runBlocking`, not `runTest`:
 * a lost increment needs two threads.
 */
class HttpClientDoubleCounterConcurrencyTest {

    @Test
    fun `an open gate counts every caller that passed through it`() = runBlocking {
        repeat(TRIALS) { trial ->
            // Given - an already-released gate, so every caller passes straight through and the
            // count is the only thing the race can spoil
            val client = GatedHttpClient(FakeHttpClient(), "example.test")
            client.release()

            // When - four coroutines on real threads pass the gate together
            fanOutOnRealThreads(THREADS) { _ ->
                repeat(CALLS_PER_THREAD) {
                    client.fetchJsonResult("https://example.test/gated")
                }
            }

            // Then - the arrival count is every caller, not every caller that won its increment
            assertEquals("trial $trial lost an arrival", THREADS * CALLS_PER_THREAD, client.arrivals)
        }
    }

    @Test
    fun `only one of several concurrent callers is the first call`() = runBlocking {
        repeat(FIRST_CALL_TRIALS) { trial ->
            // Given - the fixture that cancels its caller once, reached by four callers at once
            val client = CancellingOnceHttpClient(FakeHttpClient())
            val cancelled = AtomicInteger()

            // When - four coroutines on real threads each make one call
            fanOutOnRealThreads(THREADS) { _ ->
                val outcome = runCatching { client.fetchJsonResult("https://example.test/once") }
                if (outcome.exceptionOrNull() is CancellationException) cancelled.incrementAndGet()
            }

            // Then - exactly one caller was cancelled, so "first" named one call
            assertEquals("trial $trial cancelled more than the first call", 1, cancelled.get())
        }
    }

    private companion object {
        /** The width of an engine's per-type fan-out, the concurrency these doubles would meet. */
        const val THREADS = 4

        /**
         * A run of calls per thread, and trials well past the one a lost increment first appears
         * on. A single four-way race is lost rarely enough that one trial proves nothing and
         * cheaply enough that thousands cost under a second, so the budget is sized for margin
         * rather than for a rate, which is a property of the machine and decays.
         */
        const val CALLS_PER_THREAD = 50
        const val TRIALS = 2_000

        /**
         * A counter that names *the first* call gets one race per trial — the second call in an arm
         * is no longer first — so the trials carry the whole budget instead of sharing it with a
         * run of calls. A lost increment here cancels a caller the fixture never promised to cancel.
         */
        const val FIRST_CALL_TRIALS = 20_000
    }
}
