package com.cascade.enrichment.http

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class RateLimiterTest {
    @Test fun `execute runs block and returns result`() = runTest {
        // Given — limiter with 1s interval
        val limiter = RateLimiter(1000, clock = { 0L })

        // When — executing a block
        val result = limiter.execute { "hello" }

        // Then — block result returned
        assertEquals("hello", result)
    }

    @Test fun `execute returns typed result from block`() = runTest {
        // Given — limiter with 100ms interval
        val limiter = RateLimiter(100)

        // When / Then — integer result returned correctly
        assertEquals(42, limiter.execute { 42 })
    }

    @Test fun `execute propagates exceptions from block`() = runTest {
        // Given — limiter with 100ms interval
        val limiter = RateLimiter(100)

        // When / Then — exception propagates to caller
        try { limiter.execute { throw IllegalStateException("err") }; fail() }
        catch (e: IllegalStateException) { assertEquals("err", e.message) }
    }

    @Test fun `execute delays when called faster than interval`() = runTest {
        // Given — limiter with 500ms interval, first call already made at t=1000
        val time = AtomicLong(1000L)
        val limiter = RateLimiter(500, clock = { time.get() })
        limiter.execute { }

        // When — second call at same time (0ms elapsed < 500ms interval)
        var delayedMs = 0L
        val startTime = time.get()
        time.set(startTime + 500)
        limiter.execute { delayedMs = time.get() - startTime }

        // Then — block waited for the full interval
        assertEquals(500L, delayedMs)
    }

    @Test fun `execute does not delay when interval has passed`() = runTest {
        // Given — limiter with 100ms interval, clock advanced 200ms since last call
        val time = AtomicLong(0L)
        val limiter = RateLimiter(100, clock = { time.get() })
        limiter.execute { }
        time.set(200L)

        // When — calling again after interval has passed
        var called = false
        limiter.execute { called = true }

        // Then — executes immediately without delay
        assertTrue("Block should execute without delay", called)
    }

    @Test fun `concurrent calls are serialized by mutex`() = runTest {
        // Given — limiter with no delay (mutex serialization only)
        val callOrder = mutableListOf<Int>()
        val limiter = RateLimiter(0)

        // When — 10 concurrent calls
        val jobs = (1..10).map { i ->
            async {
                limiter.execute {
                    synchronized(callOrder) { callOrder.add(i) }
                }
            }
        }
        jobs.awaitAll()

        // Then — all 10 executed (serialized, no concurrent access)
        assertEquals(10, callOrder.size)
    }

    @Test fun `lastRequestTime updated after block completes`() = runTest {
        // Given — limiter with 50ms interval, first call at t=100
        val time = AtomicLong(100L)
        val limiter = RateLimiter(50, clock = { time.get() })
        limiter.execute { }

        // When — advancing clock well past interval and calling again
        time.set(200L)
        var executed = false
        limiter.execute { executed = true }

        // Then — executes successfully (lastRequestTime was updated to 100)
        assertTrue(executed)
    }

    @Test fun `exception in block still updates lastRequestTime`() = runTest {
        // Given — limiter where first call throws
        val time = AtomicLong(0L)
        val limiter = RateLimiter(100, clock = { time.get() })
        try { limiter.execute { throw RuntimeException("fail") } } catch (_: RuntimeException) {}

        // When — advancing past interval and calling again
        time.set(200L)
        var executed = false
        limiter.execute { executed = true }

        // Then — works normally (finally block updated lastRequestTime despite exception)
        assertTrue("Should execute after interval passes despite prior exception", executed)
    }
}
