package com.landofoz.musicmeta.http

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CircuitBreakerTest {

    @Test fun `starts in closed state allowing requests`() {
        // Given - fresh circuit breaker
        val breaker = CircuitBreaker()

        // When - state and allowRequest are read
        // Then - closed and allowing requests
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `stays closed below failure threshold`() {
        // Given - breaker with threshold of 3
        val breaker = CircuitBreaker(failureThreshold = 3)

        // When - only 2 failures (below threshold)
        breaker.recordFailure()
        breaker.recordFailure()

        // Then - still closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `opens after reaching failure threshold`() {
        // Given - breaker with threshold of 3
        val breaker = CircuitBreaker(failureThreshold = 3)

        // When - exactly 3 consecutive failures
        repeat(3) { breaker.recordFailure() }

        // Then - circuit open, requests blocked
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
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

        // Then - half-open, but Boolean checks fail closed until an actual attempt acquires
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
        requireNotNull(breaker.acquire()).abandon()
    }

    @Test fun `admits only one request while half-open`() {
        // Given - an open breaker whose cooldown has expired
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        time.set(100L)

        // When - two callers ask for admission before either records its outcome
        val first = breaker.acquire()
        val second = breaker.acquire()

        // Then - only the first caller owns the recovery probe
        assertNotNull(first)
        assertNull(second)
        first?.abandon()
    }

    @Test fun `observing half-open admission does not consume its recovery permit`() {
        // Given - a breaker whose cooldown has expired
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        breaker.recordFailure()
        time.set(100L)

        // When - callers only observe eligibility before an actual attempt
        repeat(3) { assertFalse(breaker.allowRequest()) }
        val permit = breaker.acquire()

        // Then - the actual attempt still owns the one recovery permit
        assertNotNull(permit)
        assertNull(breaker.acquire())
        permit?.abandon()
    }

    @Test fun `closed success preserves other current permits while resetting failures`() {
        // Given - concurrent closed permits after one failure below a threshold of two
        val breaker = CircuitBreaker(failureThreshold = 2)
        breaker.recordFailure()
        val successful = requireNotNull(breaker.acquire())
        val firstFailure = requireNotNull(breaker.acquire())
        val secondFailure = requireNotNull(breaker.acquire())

        // When - one permit succeeds and the other current permits then fail consecutively
        successful.recordSuccess()
        firstFailure.recordFailure()
        secondFailure.recordFailure()

        // Then - the success reset the streak but did not invalidate the other current permits
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test fun `success in half-open closes circuit`() {
        // Given - circuit in half-open state (past cooldown)
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L) // past cooldown → half-open

        // When - test request succeeds
        breaker.recordSuccess()

        // Then - circuit fully closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `failed half-open permit restarts the cooldown`() {
        // Given - a breaker that has reached half-open after its first cooldown
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        breaker.recordFailure()
        time.set(100L)
        val probe = requireNotNull(breaker.acquire())

        // When - the admitted recovery probe fails
        probe.recordFailure()

        // Then - a new full cooldown starts instead of admitting another probe immediately
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
    }

    @Test fun `obsolete closed permit cannot close a newer open circuit`() {
        // Given - a closed attempt is in flight when later failures open the breaker
        val breaker = CircuitBreaker(failureThreshold = 1)
        val oldAttempt = requireNotNull(breaker.acquire())
        breaker.recordFailure()

        // When - the old attempt reports success after the circuit opened
        oldAttempt.recordSuccess()

        // Then - its obsolete completion cannot erase the newer failure
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test fun `reset invalidates an obsolete half-open permit`() {
        // Given - a half-open permit exists before an explicit reset
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        breaker.recordFailure()
        time.set(100L)
        val obsoleteProbe = requireNotNull(breaker.acquire())

        // When - reset closes the breaker and the old probe later fails
        breaker.reset()
        obsoleteProbe.recordFailure()

        // Then - reset remains closed because the old permit no longer owns this generation
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `failure in half-open reopens circuit`() {
        // Given - circuit in half-open state (past cooldown)
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        breaker.recordFailure()
        time.set(200L) // past cooldown → half-open

        // When - test request fails
        breaker.recordFailure()

        // Then - circuit reopens
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
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
        assertTrue(breaker.allowRequest())
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
        assertFalse(breaker.allowRequest())

        // When - clock advances to 4.9s after opening
        time.set(5900L)
        // Then - still blocked
        assertFalse(breaker.allowRequest())

        // When - clock advances to exactly 5s (cooldown expired)
        time.set(6000L)
        // Then - Boolean checks still fail closed, but an actual probe can acquire
        assertFalse(breaker.allowRequest())
        requireNotNull(breaker.acquire()).abandon()
    }
}
