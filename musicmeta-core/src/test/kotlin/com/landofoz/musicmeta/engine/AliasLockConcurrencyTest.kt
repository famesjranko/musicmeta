package com.landofoz.musicmeta.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * What `ResolvedEntityNames.aliasLock` costs when the source under it is a network call that does
 * not answer. The lock is held across that call, so every property here is about what the readers
 * queued behind it can still do — finish on the call's deadline, run on a dispatcher the source
 * needs, and stay independent of another call's hung source.
 *
 * Real time, not `runTest`'s virtual clock: the question is whether a waiter is parked on a thread
 * or merely suspended, and a virtual clock cannot tell those apart.
 */
// InjectDispatcher/SleepInsteadOfDelay: both are the subject. A real pool is what makes thread
// starvation possible at all, and a blocking sleep is what a consumer client that is not
// suspension-aware does to the thread the lock holder needs.
@Suppress("InjectDispatcher", "SleepInsteadOfDelay")
class AliasLockConcurrencyTest {

    @Test
    fun `a source that never answers still lets every waiter settle on the call's deadline`() {
        // Given - five readers behind one source that suspends forever
        val names = ResolvedEntityNames()
        val entered = AtomicInteger()
        names.offerAliases {
            entered.incrementAndGet()
            suspendCancellableCoroutine<List<AlternativeName>> { }
        }

        // When - the fan-out's deadline expires while the first reader holds the lock
        var settled: List<List<AlternativeName>>? = null
        val elapsed = measureTimeMillis {
            runBlocking(Dispatchers.Default) {
                settled = withTimeoutOrNull(DEADLINE_MS) {
                    withContext(names) { List(READERS) { async { names.aliases() } }.awaitAll() }
                }
            }
        }

        // Then - the deadline cancelled all five, and no waiter outlived it
        assertNull("expected the deadline to expire, not the readers to answer", settled)
        assertEquals("only the lock holder should reach the source", 1, entered.get())
        assertTrue("readers outlived the deadline by ${elapsed - DEADLINE_MS}ms", elapsed < DEADLINE_MS * 4)
    }

    @Test
    fun `a source that blocks its thread cannot be starved by the waiters queued behind it`() {
        // Given - a source that blocks the one thread its dispatcher has, with waiters behind it
        val names = ResolvedEntityNames()
        names.offerAliases {
            Thread.sleep(BLOCK_MS)
            POOL
        }

        // When - every reader runs on that single thread
        var settled: List<List<AlternativeName>>? = null
        val elapsed = measureTimeMillis {
            runBlocking(singleThread) {
                settled = withTimeoutOrNull(DEADLINE_MS) {
                    withContext(names) { List(READERS) { async { names.aliases() } }.awaitAll() }
                }
            }
        }

        // Then - the blocked thread was released and every waiter got the one answer
        assertEquals(List(READERS) { POOL }, settled)
        assertTrue("one blocking source cost ${elapsed}ms across $READERS readers", elapsed < DEADLINE_MS)
    }

    @Test
    fun `a source wrapping a blocking client in runBlocking answers on a single thread`() {
        // Given - a consumer client that bridges its synchronous call with runBlocking
        val names = ResolvedEntityNames()
        names.offerAliases { runBlocking { Thread.sleep(BLOCK_MS); POOL } }

        // When - readers on one thread ask for the pool
        var settled: List<List<AlternativeName>>? = null
        runBlocking(singleThread) {
            settled = withTimeoutOrNull(DEADLINE_MS) {
                withContext(names) { List(READERS) { async { names.aliases() } }.awaitAll() }
            }
        }

        // Then - the nested event loop did not wedge against the lock
        assertEquals(List(READERS) { POOL }, settled)
    }

    @Test
    fun `one call's hung source does not reach another call's readers`() {
        // Given - two calls, each with its own names channel, one of them hung
        val hung = ResolvedEntityNames()
        hung.offerAliases { suspendCancellableCoroutine<List<AlternativeName>> { } }
        val healthy = ResolvedEntityNames()
        healthy.offerAliases { POOL }

        // When - the healthy call reads its pool while the hung call's readers are queued
        var answered: List<AlternativeName>? = null
        runBlocking(Dispatchers.Default) {
            val stuckJob = Job()
            val stuck = CoroutineScope(stuckJob + Dispatchers.Default)
            repeat(READERS) { stuck.async(hung) { hung.aliases() } }
            answered = withTimeoutOrNull(DEADLINE_MS) { withContext(healthy) { healthy.aliases() } }
            stuckJob.cancel()
        }

        // Then - the second call answered on its own source
        assertEquals(POOL, answered)
    }

    @Test
    fun `a reader cancelled mid-lookup leaves the work it did for the readers queued behind it`() {
        // Given - a source slower than the first reader's own provider deadline
        val names = ResolvedEntityNames()
        val calls = AtomicInteger()
        names.offerAliases {
            calls.incrementAndGet()
            delay(SOURCE_MS)
            POOL
        }

        // When - the reader holding the lock is cancelled by its own timeout, and four wait
        var settled: List<List<AlternativeName>>? = null
        runBlocking(Dispatchers.Default) {
            withContext(names) {
                val first = async { withTimeoutOrNull(SOURCE_MS / 4) { names.aliases() } }
                delay(SOURCE_MS / 8)
                val rest = List(READERS - 1) { async { names.aliases() } }
                first.await()
                settled = rest.awaitAll()
            }
        }

        // Then - the queued readers were served by the lookup already in flight
        assertEquals(List(READERS - 1) { POOL }, settled)
        assertEquals("the cancelled reader's lookup was thrown away", 1, calls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThread = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val READERS = 5
        const val DEADLINE_MS = 500L
        const val BLOCK_MS = 50L
        const val SOURCE_MS = 400L
        val POOL = listOf(AlternativeName("Tokyo Jihen", official = true))
    }
}
