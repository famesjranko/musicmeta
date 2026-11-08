package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.http.EnrichDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What `ResolvedEntityNames.aliasLock` costs when the source under it is a network call that does
 * not answer. The lock is held only long enough to install the shared lookup, so every property
 * here is about what the readers queued behind that lookup can still do — finish on the call's
 * deadline, run on a dispatcher the source needs, and stay independent of another call's hung
 * source.
 *
 * The blocking-source tests use real threads, not `runTest`'s virtual clock: the question is
 * whether a waiter is parked on a thread or merely suspended, and a virtual clock cannot tell
 * those apart. The production-shaped budget test carries an [EnrichDeadline], as one under
 * `enrich()` does, so a source that never answers is bounded as it is in production.
 *
 * The two tests that compare caller and lookup deadlines use `runTest`'s virtual clock. They pass
 * the lookup budget explicitly and do not install an [EnrichDeadline], so the deadlines are
 * separate scheduled events on one clock.
 *
 * No property here is a measurement of elapsed time, because none of them is one: a parked waiter
 * is a run that never returns, not a run that returns late, and a shared lookup that outlives its
 * budget is a cancellation that never arrives. Wall clock appears only as [GUARD_MS], a bound
 * wide enough that a loaded machine cannot reach it and only a hang can.
 */
// InjectDispatcher/SleepInsteadOfDelay: both are the subject. A real pool is what makes thread
// starvation possible at all, and a blocking sleep is what a consumer client that is not
// suspension-aware does to the thread the lock holder needs.
@Suppress("InjectDispatcher", "SleepInsteadOfDelay")
class AliasLockConcurrencyTest {

    // No dispatcher: the shared lookup runs on the reader's, so this carries only the job an engine
    // gives it — the scope whose cancellation is `close()`.
    private val lookupScope = CoroutineScope(SupervisorJob())

    @After fun stopLookups() {
        lookupScope.cancel()
    }

    @Test
    fun `a source that never answers lets every reader settle on the caller deadline`() = runTest {
        // Given - five readers behind one source that suspends until the call cleans it up
        val lookupScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            val names = ResolvedEntityNames(lookupScope, LONG_LOOKUP_BUDGET_MS)
            val entered = AtomicInteger()
            val readerBodies = AtomicInteger()
            val cancelledReaders = AtomicInteger()
            val sourceSuspended = CompletableDeferred<Unit>()
            val sourceCancelled = CompletableDeferred<Unit>()
            names.offerAliases {
                entered.incrementAndGet()
                suspendCancellableCoroutine<List<AlternativeName>> { c ->
                    sourceSuspended.complete(Unit)
                    c.invokeOnCancellation { sourceCancelled.complete(Unit) }
                }
            }

            // When - the caller deadline expires while the lookup is still in flight
            val call = async {
                withTimeoutOrNull(CALLER_DEADLINE_MS) {
                    withContext(names) {
                        List(READERS) {
                            async {
                                readerBodies.incrementAndGet()
                                try {
                                    names.aliases()
                                } catch (cancelled: CancellationException) {
                                    cancelledReaders.incrementAndGet()
                                    throw cancelled
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            testScheduler.runCurrent()
            assertEquals(READERS, readerBodies.get())
            assertTrue(sourceSuspended.isCompleted)

            testScheduler.advanceTimeBy(CALLER_DEADLINE_MS)
            testScheduler.runCurrent()
            assertTrue("a reader outlived the caller deadline", call.isCompleted)
            val settled = call.await()
            names.cancelPendingLookup()
            testScheduler.runCurrent()

            // Then - the caller deadline cancelled every reader and the source they were waiting on
            assertNull("expected the deadline to expire, not the readers to answer", settled)
            assertEquals("the lookup should be opened once, not per reader", 1, entered.get())
            assertEquals("a reader settled on something other than the deadline", READERS, cancelledReaders.get())
            assertTrue("the source was never cancelled", sourceCancelled.isCompleted)
        } finally {
            lookupScope.cancel()
        }
    }

    @Test
    fun `a lookup budget expiry serves empty aliases and cancels a waiting reader`() = runTest {
        // Given - four readers waiting on a lookup with a shorter budget and one reader held back
        val lookupScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            val names = ResolvedEntityNames(lookupScope, LOOKUP_BUDGET_MS)
            val readerBodies = AtomicInteger()
            val cancelledReaders = AtomicInteger()
            val emptyReaders = AtomicInteger()
            val holdReader = CompletableDeferred<Unit>()
            val sourceEntered = CompletableDeferred<Unit>()
            val sourceCancelled = CompletableDeferred<Unit>()
            names.offerAliases {
                sourceEntered.complete(Unit)
                suspendCancellableCoroutine<List<AlternativeName>> { c ->
                    c.invokeOnCancellation { sourceCancelled.complete(Unit) }
                }
            }

            // When - the lookup budget expires before the caller deadline
            val call = async {
                withTimeoutOrNull(CALLER_DEADLINE_MS) {
                    withContext(names) {
                        List(READERS) { index ->
                            async {
                                readerBodies.incrementAndGet()
                                try {
                                    if (index == 0) holdReader.await()
                                    val aliases = names.aliases()
                                    if (aliases.isEmpty()) emptyReaders.incrementAndGet()
                                    aliases
                                } catch (cancelled: CancellationException) {
                                    cancelledReaders.incrementAndGet()
                                    throw cancelled
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            testScheduler.runCurrent()
            assertEquals(READERS, readerBodies.get())
            assertTrue(sourceEntered.isCompleted)

            testScheduler.advanceTimeBy(LOOKUP_BUDGET_MS)
            testScheduler.runCurrent()
            assertTrue(sourceCancelled.isCompleted)
            assertEquals(READERS - 1, emptyReaders.get())
            assertTrue(!call.isCompleted)

            testScheduler.advanceTimeBy(CALLER_DEADLINE_MS - LOOKUP_BUDGET_MS)
            testScheduler.runCurrent()
            val settled = call.await()

            // Then - lookup expiry served empty aliases, and the caller deadline cancelled the held reader
            assertNull(settled)
            assertEquals(READERS - 1, emptyReaders.get())
            assertEquals(1, cancelledReaders.get())
            assertTrue(sourceCancelled.isCompleted)
        } finally {
            lookupScope.cancel()
        }
    }

    @Test
    fun `a source that never answers is abandoned when the call's budget runs out`() {
        // Given - a source that reports being cancelled, opened under a budget it cannot meet
        val names = ResolvedEntityNames(lookupScope)
        val abandoned = CountDownLatch(1)
        names.offerAliases {
            suspendCancellableCoroutine<List<AlternativeName>> { it.invokeOnCancellation { abandoned.countDown() } }
        }

        // When - the reader that opened it gives up long before the budget does
        runBlocking(Dispatchers.Default) {
            withTimeoutOrNull(DEADLINE_MS / 5) { readAll(names, DEADLINE_MS) }
        }

        // Then - the lookup stopped on its own budget rather than running for the JVM's life
        assertTrue(
            "the shared lookup was never abandoned",
            abandoned.await(GUARD_MS, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `a source that blocks its thread cannot be starved by the waiters queued behind it`() {
        // Given - a source that blocks the one thread its dispatcher has, with waiters behind it
        val names = ResolvedEntityNames(lookupScope)
        names.offerAliases {
            Thread.sleep(BLOCK_MS)
            POOL
        }

        // When - every reader runs on that single thread
        var settled: List<List<AlternativeName>>? = null
        runBlocking(singleThread) {
            settled = withTimeoutOrNull(GUARD_MS) { readAll(names) }
        }

        // Then - the blocked thread was released and every waiter got the one answer
        assertEquals(List(READERS) { POOL }, settled)
    }

    @Test
    fun `a source wrapping a blocking client in runBlocking answers on a single thread`() {
        // Given - a consumer client that bridges its synchronous call with runBlocking
        val names = ResolvedEntityNames(lookupScope)
        names.offerAliases { runBlocking { Thread.sleep(BLOCK_MS); POOL } }

        // When - readers on one thread ask for the pool
        var settled: List<List<AlternativeName>>? = null
        runBlocking(singleThread) {
            settled = withTimeoutOrNull(GUARD_MS) { readAll(names) }
        }

        // Then - the nested event loop did not wedge against the lock
        assertEquals(List(READERS) { POOL }, settled)
    }

    @Test
    fun `one call's hung source does not reach another call's readers`() {
        // Given - two calls, each with its own names channel, one of them hung
        val hung = ResolvedEntityNames(lookupScope)
        val entered = CompletableDeferred<Unit>()
        hung.offerAliases {
            entered.complete(Unit)
            suspendCancellableCoroutine<List<AlternativeName>> { }
        }
        val healthy = ResolvedEntityNames(lookupScope)
        healthy.offerAliases { POOL }

        // When - the healthy call reads its pool while the hung call's readers are queued
        var answered: List<AlternativeName>? = null
        runBlocking(Dispatchers.Default) {
            val stuckJob = Job()
            val stuck = CoroutineScope(stuckJob + Dispatchers.Default + EnrichDeadline(DEADLINE_MS))
            repeat(READERS) { stuck.async(hung) { hung.aliases() } }
            entered.await()
            answered = withTimeoutOrNull(GUARD_MS) {
                withContext(EnrichDeadline(GUARD_MS) + healthy) { healthy.aliases() }
            }
            stuckJob.cancel()
            hung.cancelPendingLookup()
        }

        // Then - the second call answered on its own source
        assertEquals(POOL, answered)
    }

    @Test
    fun `a reader cancelled mid-lookup leaves the work it did for the readers queued behind it`() {
        // Given - a source that answers only once released, slower than the first reader's timeout
        val names = ResolvedEntityNames(lookupScope)
        val calls = AtomicInteger()
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        names.offerAliases {
            calls.incrementAndGet()
            opened.complete(Unit)
            release.await()
            POOL
        }

        // When - the reader that opened the lookup is cancelled by its own timeout, and four wait
        var abandoned: List<AlternativeName>? = POOL
        var settled: List<List<AlternativeName>>? = null
        runBlocking(Dispatchers.Default) {
            withContext(EnrichDeadline(GUARD_MS) + names) {
                val first = async { withTimeoutOrNull(PROVIDER_MS) { names.aliases() } }
                opened.await()
                val rest = List(READERS - 1) { async { names.aliases() } }
                abandoned = first.await()
                release.complete(Unit)
                settled = rest.awaitAll()
            }
        }

        // Then - the queued readers were served by the lookup already in flight
        assertNull("the first reader outlived its own timeout", abandoned)
        assertEquals(List(READERS - 1) { POOL }, settled)
        assertEquals("the cancelled reader's lookup was thrown away", 1, calls.get())
    }

    /**
     * [READERS] concurrent readers of [names], under the deadline a reader carries under `enrich()`.
     *
     * [deadlineMs] defaults to [GUARD_MS] so the deadline fires only where a test is about the
     * deadline; [onReaderCancelled] runs on the path a reader takes when one reaches it.
     */
    private suspend fun readAll(
        names: ResolvedEntityNames,
        deadlineMs: Long = GUARD_MS,
        onReaderCancelled: () -> Unit = {},
    ): List<List<AlternativeName>> =
        withContext(EnrichDeadline(deadlineMs) + names) {
            List(READERS) {
                async {
                    try {
                        names.aliases()
                    } catch (cancelled: CancellationException) {
                        onReaderCancelled()
                        throw cancelled
                    }
                }
            }.awaitAll()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThread = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val READERS = 5

        /** A call's real-clock deadline: the budget that a shared lookup opened under it is abandoned on. */
        const val DEADLINE_MS = 500L

        /**
         * A bound no correct run can reach, however loaded the machine — it stands in for "never
         * returns", so only a parked waiter or a lookup nothing cancels can spend it.
         */
        const val GUARD_MS = DEADLINE_MS * 20

        /** A provider's own read timeout, fired while the shared lookup is still held. */
        const val PROVIDER_MS = 250L
        const val LOOKUP_BUDGET_MS = 100L
        const val CALLER_DEADLINE_MS = 200L
        const val LONG_LOOKUP_BUDGET_MS = CALLER_DEADLINE_MS * 2
        const val BLOCK_MS = 50L
        val POOL = listOf(AlternativeName("Tokyo Jihen", official = true))
    }
}
