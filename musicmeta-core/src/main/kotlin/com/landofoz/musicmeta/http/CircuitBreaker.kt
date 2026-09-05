package com.landofoz.musicmeta.http

/**
 * Tracks consecutive failures for a provider and short-circuits calls
 * when the failure threshold is reached.
 *
 * States:
 * - CLOSED: normal operation, requests pass through
 * - OPEN: too many failures, requests are rejected immediately
 * - HALF_OPEN: after cooldown, one request is allowed through to test recovery
 *
 * Thread-safe via synchronized blocks. Designed to be paired 1:1 with
 * a provider instance, same as [RateLimiter].
 *
 * @param failureThreshold Consecutive failures before opening the circuit
 * @param cooldownMs How long the circuit stays open before allowing a test request
 * @param clock Time source (injectable for testing)
 */
internal class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var consecutiveFailures = 0
    private var openedAt = 0L

    /**
     * Probe instrumentation: how many times this breaker has gone from below the threshold to at
     * it. Counted inside the same monitor as the transition, so a concurrent fan-out cannot lose
     * or double an opening.
     */
    var openings: Int = 0
        @Synchronized get
        private set

    /**
     * Probe instrumentation: the highest consecutive-failure count this breaker ever held. How far
     * short of [failureThreshold] a run stopped is what says whether "it never opened" was a margin
     * or a coin toss.
     */
    var peakConsecutiveFailures: Int = 0
        @Synchronized get
        private set

    val state: State
        @Synchronized get() = when {
            consecutiveFailures < failureThreshold -> State.CLOSED
            clock() - openedAt >= cooldownMs -> State.HALF_OPEN
            else -> State.OPEN
        }

    /** Returns true if a request should be allowed through. */
    @Synchronized
    fun allowRequest(): Boolean = when (state) {
        State.CLOSED -> true
        State.HALF_OPEN -> true
        State.OPEN -> false
    }

    /** Record a successful call. Resets the failure counter. */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /** Record a failed call. Opens the circuit if threshold is reached. */
    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures > peakConsecutiveFailures) peakConsecutiveFailures = consecutiveFailures
        if (consecutiveFailures >= failureThreshold) {
            // Probe instrumentation: an opening is the failure that first reaches the threshold.
            // A later failure while already at or above it re-stamps openedAt without reopening.
            if (consecutiveFailures == failureThreshold) openings++
            openedAt = clock()
        }
    }

    /** Force-reset to closed state. */
    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        openedAt = 0L
    }

    enum class State { CLOSED, HALF_OPEN, OPEN }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
