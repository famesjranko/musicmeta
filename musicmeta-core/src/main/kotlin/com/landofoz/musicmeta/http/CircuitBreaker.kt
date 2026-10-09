package com.landofoz.musicmeta.http

/**
 * Tracks consecutive failures for a provider and short-circuits calls
 * when the failure threshold is reached.
 *
 * States:
 * - CLOSED: normal operation, requests pass through
 * - OPEN: too many failures, requests are rejected immediately
 * - HALF_OPEN: after cooldown, one request (the probe) is allowed through to test recovery; every
 *   other caller is refused until the probe settles
 *
 * A call is admitted by [acquire], which returns an [Admission] the caller must settle exactly once:
 * [Admission.recordSuccess], [Admission.recordFailure], or [Admission.abandon] for a call that ended
 * without an outcome (its caller was cancelled). An admission belongs to the generation it was issued
 * in, and a generation ends when the breaker opens or closes, so an outcome that arrives after that
 * — a call admitted before the breaker opened, or a probe already abandoned — changes nothing.
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
    private var generation = 0L
    private var probe: Admission? = null

    val state: State
        @Synchronized get() = when {
            consecutiveFailures < failureThreshold -> State.CLOSED
            clock() - openedAt >= cooldownMs -> State.HALF_OPEN
            else -> State.OPEN
        }

    /**
     * Admits one call, or returns null when the breaker refuses it. In HALF_OPEN the first caller
     * becomes the probe and holds the only admission until it settles.
     */
    @Synchronized
    fun acquire(): Admission? = when (state) {
        State.CLOSED -> Admission(generation)
        State.HALF_OPEN -> if (probe == null) Admission(generation).also { probe = it } else null
        State.OPEN -> null
    }

    /**
     * Whether [acquire] would admit a call now, without taking the probe. A read, not a promise:
     * another caller can take the probe before this one acquires.
     */
    @Synchronized
    fun wouldAdmit(): Boolean = when (state) {
        State.CLOSED -> true
        State.HALF_OPEN -> probe == null
        State.OPEN -> false
    }

    /** Record a successful call that holds no [Admission]. Resets the failure counter. */
    @Synchronized
    fun recordSuccess() = succeed()

    /** Record a failed call that holds no [Admission]. Opens the circuit if threshold is reached. */
    @Synchronized
    fun recordFailure() = fail()

    /** Force-reset to closed state. Any outstanding [Admission] becomes stale. */
    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        openedAt = 0L
        endGeneration()
    }

    private fun succeed() {
        val wasOpen = consecutiveFailures >= failureThreshold
        consecutiveFailures = 0
        // Closing from OPEN or HALF_OPEN ends the generation; a CLOSED success must not, or it
        // would discard the outcome of every other call in flight.
        if (wasOpen) endGeneration()
    }

    private fun fail() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openedAt = clock()
            endGeneration()
        }
    }

    private fun endGeneration() {
        generation++
        probe = null
    }

    /**
     * One admitted call. Settling it more than once, or after its generation ended, is a no-op: a
     * late outcome from a call the breaker has already moved past must not move it again.
     */
    inner class Admission internal constructor(private val issuedIn: Long) {
        private var settled = false

        fun recordSuccess() = settle { succeed() }

        fun recordFailure() = settle { fail() }

        /** Frees the probe without an outcome, so the next HALF_OPEN caller is admitted at once. */
        fun abandon() = settle { }

        private fun settle(outcome: () -> Unit) {
            synchronized(this@CircuitBreaker) {
                if (settled) return
                settled = true
                if (probe === this) probe = null
                if (issuedIn == generation) outcome()
            }
        }
    }

    enum class State { CLOSED, HALF_OPEN, OPEN }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
