package com.cascade.enrichment.http

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CircuitBreakerTest {

    @Test fun `starts in closed state allowing requests`() {
        // Given — fresh circuit breaker
        val breaker = CircuitBreaker()

        // When / Then — closed and allowing requests
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `stays closed below failure threshold`() {
        // Given — breaker with threshold of 3
        val breaker = CircuitBreaker(failureThreshold = 3)

        // When — only 2 failures (below threshold)
        breaker.recordFailure()
        breaker.recordFailure()

        // Then — still closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `opens after reaching failure threshold`() {
        // Given — breaker with threshold of 3
        val breaker = CircuitBreaker(failureThreshold = 3)

        // When — exactly 3 consecutive failures
        repeat(3) { breaker.recordFailure() }

        // Then — circuit open, requests blocked
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
    }

    @Test fun `success resets consecutive failure count`() {
        // Given — breaker with threshold 3, 2 failures recorded
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.recordFailure()
        breaker.recordFailure()

        // When — success resets count, then 2 more failures
        breaker.recordSuccess()
        breaker.recordFailure()
        breaker.recordFailure()

        // Then — only 2 consecutive (not 4), still closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `transitions to half-open after cooldown expires`() {
        // Given — open circuit (2 failures at t=1000), cooldown = 5s
        val time = AtomicLong(1000L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 5000, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        // When — advancing clock past cooldown (t=7000, 6s since opening)
        time.set(7000L)

        // Then — half-open, allows one test request
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `success in half-open closes circuit`() {
        // Given — circuit in half-open state (past cooldown)
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L) // past cooldown → half-open

        // When — test request succeeds
        breaker.recordSuccess()

        // Then — circuit fully closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `failure in half-open reopens circuit`() {
        // Given — circuit in half-open state (past cooldown)
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L) // past cooldown → half-open

        // When — test request fails
        breaker.recordFailure()

        // Then — circuit reopens
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
    }

    @Test fun `reset forces circuit back to closed`() {
        // Given — open circuit
        val breaker = CircuitBreaker(failureThreshold = 1)
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        // When — force reset
        breaker.reset()

        // Then — fully closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `open circuit blocks requests until cooldown expires`() {
        // Given — open circuit at t=1000, cooldown = 5s
        val time = AtomicLong(1000L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 5000, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()

        // When / Then — blocked at 1s after opening
        time.set(2000L)
        assertFalse(breaker.allowRequest())

        // When / Then — still blocked at 4.9s
        time.set(5900L)
        assertFalse(breaker.allowRequest())

        // When / Then — allowed at exactly 5s (cooldown expired)
        time.set(6000L)
        assertTrue(breaker.allowRequest())
    }
}
