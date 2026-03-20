package com.cascade.enrichment.http

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
class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var consecutiveFailures = 0
    private var openedAt = 0L

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
        if (consecutiveFailures >= failureThreshold) {
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
