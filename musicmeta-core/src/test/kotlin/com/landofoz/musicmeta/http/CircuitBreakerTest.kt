package com.landofoz.musicmeta.http

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class CircuitBreakerTest {

    @Test fun `starts in closed state allowing requests`() {
        // Given - fresh circuit breaker
        val breaker = CircuitBreaker()

        // When - state is read and a call is admitted
        // Then - closed and admitting requests
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun `stays closed below failure threshold`() {
        // Given - breaker with threshold of 3
        val breaker = CircuitBreaker(failureThreshold = 3)

        // When - only 2 failures (below threshold)
        breaker.recordFailure()
        breaker.recordFailure()

        // Then - still closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun `opens after reaching failure threshold`() {
        // Given - breaker with threshold of 3
        val breaker = CircuitBreaker(failureThreshold = 3)

        // When - exactly 3 consecutive failures
        repeat(3) { breaker.recordFailure() }

        // Then - circuit open, requests blocked
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertNull(breaker.acquire())
    }

    @Test fun `success resets consecutive failure count`() {
        // Given - breaker with threshold 3, 2 failures recorded
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.recordFailure()
        breaker.recordFailure()

        // When - success resets count, then 2 more failures
        breaker.recordSuccess()
        breaker.recordFailure()
        breaker.recordFailure()

        // Then - only 2 consecutive (not 4), still closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `transitions to half-open after cooldown expires`() {
        // Given - open circuit (2 failures at t=1000), cooldown = 5s
        val time = AtomicLong(1000L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 5000, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        // When - advancing clock past cooldown (t=7000, 6s since opening)
        time.set(7000L)

        // Then - half-open, allows one test request
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun `success in half-open closes circuit`() {
        // Given - circuit in half-open state (past cooldown)
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L) // past cooldown → half-open

        // When - the probe succeeds
        checkNotNull(breaker.acquire()).recordSuccess()

        // Then - circuit fully closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `failure in half-open reopens circuit`() {
        // Given - circuit in half-open state (past cooldown)
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L) // past cooldown → half-open

        // When - the probe fails
        checkNotNull(breaker.acquire()).recordFailure()

        // Then - circuit reopens
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertNull(breaker.acquire())
    }

    @Test fun `reset forces circuit back to closed`() {
        // Given - open circuit
        val breaker = CircuitBreaker(failureThreshold = 1)
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        // When - force reset
        breaker.reset()

        // Then - fully closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun `open circuit blocks requests until cooldown expires`() {
        // Given - open circuit at t=1000, cooldown = 5s
        val time = AtomicLong(1000L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 5000, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()

        // When - clock advances to 1s after opening
        time.set(2000L)
        // Then - still blocked
        assertFalse(breaker.wouldAdmit())

        // When - clock advances to 4.9s after opening
        time.set(5900L)
        // Then - still blocked
        assertFalse(breaker.wouldAdmit())

        // When - clock advances to exactly 5s (cooldown expired)
        time.set(6000L)
        // Then - allowed
        assertTrue(breaker.wouldAdmit())
    }

    /** A breaker that opened at t=0 and is half-open at t=200 (threshold 2, cooldown 100). */
    private fun halfOpen(time: AtomicLong): CircuitBreaker {
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L)
        return breaker
    }

    @Test fun `half-open admits one probe and refuses the next call until it settles`() {
        // Given - a breaker that is half-open
        val breaker = halfOpen(AtomicLong(0L))

        // When - two calls ask to be admitted one after the other
        val first = breaker.acquire()
        val second = breaker.acquire()

        // Then - the first is the probe and the second is refused
        assertNotNull(first)
        assertNull(second)
    }

    @Test fun `half-open admits exactly one of many callers released together`() {
        // Given - a half-open breaker and 16 threads waiting at a start barrier
        val breaker = halfOpen(AtomicLong(0L))
        val callers = 16
        val barrier = CyclicBarrier(callers)
        val admitted = AtomicInteger()

        // When - every thread asks to be admitted at once, and none settles
        val threads = List(callers) {
            thread {
                barrier.await()
                if (breaker.acquire() != null) admitted.incrementAndGet()
            }
        }
        threads.forEach { it.join() }

        // Then - exactly one was admitted
        assertEquals(1, admitted.get())
    }

    @Test fun `a failed probe reopens the circuit with a cooldown counted from that failure`() {
        // Given - a half-open breaker (cooldown 100) whose probe is in flight
        val time = AtomicLong(0L)
        val breaker = halfOpen(time)
        val probe = checkNotNull(breaker.acquire())

        // When - the probe fails at t=250
        time.set(250L)
        probe.recordFailure()

        // Then - still open 99ms later, and half-open once the cooldown from t=250 has passed
        time.set(349L)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        time.set(350L)
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
    }

    @Test fun `an abandoned probe frees the slot without a recorded outcome or a new cooldown`() {
        // Given - a half-open breaker whose probe is in flight
        val breaker = halfOpen(AtomicLong(0L))
        val probe = checkNotNull(breaker.acquire())

        // When - the probe is abandoned
        probe.abandon()

        // Then - the breaker is still half-open and the next caller is admitted as the new probe
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun `a success from a call admitted before the breaker opened does not close it`() {
        // Given - a call admitted while closed, then the breaker opens on two other failures
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { 0L })
        val early = checkNotNull(breaker.acquire())
        breaker.recordFailure()
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        // When - the early call finishes late, with a success
        early.recordSuccess()

        // Then - the breaker is still open
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test fun `a failure from a call admitted before the breaker opened does not spend the probe`() {
        // Given - a call admitted while closed, the breaker opens, then cools down to half-open
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        val early = checkNotNull(breaker.acquire())
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L)

        // When - the early call finishes late, with a failure
        early.recordFailure()

        // Then - the breaker is still half-open and its probe is still free
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun `a success from an abandoned probe leaves the current probe in place`() {
        // Given - a probe that was abandoned, and a second probe now in flight
        val breaker = halfOpen(AtomicLong(0L))
        val abandoned = checkNotNull(breaker.acquire())
        abandoned.abandon()
        checkNotNull(breaker.acquire())

        // When - the abandoned probe settles late, with a success
        abandoned.recordSuccess()

        // Then - the breaker is still half-open and the second probe still holds the slot
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertNull(breaker.acquire())
    }

    @Test fun `a failure from an abandoned probe leaves the current probe in place`() {
        // Given - a probe that was abandoned, and a second probe now in flight
        val breaker = halfOpen(AtomicLong(0L))
        val abandoned = checkNotNull(breaker.acquire())
        abandoned.abandon()
        checkNotNull(breaker.acquire())

        // When - the abandoned probe settles late, with a failure
        abandoned.recordFailure()

        // Then - the breaker is still half-open and the second probe still holds the slot
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertNull(breaker.acquire())
    }

    @Test fun `wouldAdmit reads without taking the probe`() {
        // Given - a half-open breaker
        val breaker = halfOpen(AtomicLong(0L))

        // When - it is read twice, then a caller asks to be admitted
        val first = breaker.wouldAdmit()
        val second = breaker.wouldAdmit()
        val probe = breaker.acquire()

        // Then - both reads said yes and the caller still received the probe
        assertTrue(first && second)
        assertNotNull(probe)
    }
}
