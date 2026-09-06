package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [FakeHttpClient] is handed to provider chains the engine fans out over — one `launch` per type on
 * `Dispatchers.Default`, real threads — so its request logs and its sequenced stubs must tolerate
 * concurrent callers, or a test asserting on a request count reads whichever call lost the race.
 * `runBlocking`, not `runTest`: the race only exists on a real dispatcher.
 *
 * Every claim here is a count or a set, never a duration: a lost write is a wrong count, which is
 * available structurally and says nothing about the runner's load (`docs/pitfalls.md` §38).
 */
class FakeHttpClientConcurrencyTest {

    @Test
    fun `concurrent requests all land in the request logs`() = runBlocking {
        // Given - the four-way fan-out an engine subjects one client to, each arm issuing a run of
        // requests so that one trial offers many chances to lose a write
        val urls = (0 until THREADS).map { thread ->
            (0 until WRITES_PER_THREAD).map { "https://example.test/fake?thread=$thread&call=$it" }
        }
        val expected = urls.flatten().toSet()

        repeat(TRIALS) { trial ->
            val client = FakeHttpClient()

            // When - four coroutines on real threads issue their requests as simultaneously as a
            // spin barrier can arrange
            fanOutOnRealThreads(THREADS) { thread ->
                for (url in urls[thread]) {
                    client.fetchJsonResult(url)
                }
            }

            // Then - both logs hold every request, none of them lost to a racing writer
            assertEquals("trial $trial lost a URL", expected.size, client.requestedUrls.size)
            assertEquals("trial $trial lost a header map", expected.size, client.requestedHeaders.size)
            assertEquals("trial $trial dropped a distinct URL", expected, client.requestedUrls.toSet())
        }
    }

    @Test
    fun `concurrent callers each take their own answer from a sequenced stub`() = runBlocking {
        // Given - one URL stubbed with an answer for every call the fan-out will make
        val answers = (0 until THREADS * WRITES_PER_THREAD).map { "redirect-target-$it" }

        repeat(TRIALS) { trial ->
            val client = FakeHttpClient()
            client.givenJsonResponsesInTurn("example.test", *answers.toTypedArray())
            val received = CopyOnWriteArrayList<String>()

            // When - four coroutines on real threads pop that one queue at once
            fanOutOnRealThreads(THREADS) { _ ->
                repeat(WRITES_PER_THREAD) {
                    val result = client.fetchRedirectUrlResult("https://example.test/redirect")
                    received.add((result as HttpResult.Ok<String>).body)
                }
            }

            // Then - the queue handed out each answer exactly once
            assertEquals("trial $trial served an answer twice or not at all", answers.toSet(), received.toSet())
        }
    }

    private companion object {
        /** The width of an engine's per-type fan-out, the concurrency these doubles actually meet. */
        const val THREADS = 4

        /**
         * A run of writes per thread, and trials well past the one a lost write first appears on.
         * A single four-way race is lost rarely enough that one trial proves nothing and cheaply
         * enough that thousands cost under a second, so the budget is sized for margin rather than
         * for a rate, which is a property of the machine and decays.
         */
        const val WRITES_PER_THREAD = 50
        const val TRIALS = 2_000
    }
}
