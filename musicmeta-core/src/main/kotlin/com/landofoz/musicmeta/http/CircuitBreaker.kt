package com.landofoz.musicmeta.http

/** Tracks provider failures and owns the one recovery probe allowed after cooldown. */
internal class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var consecutiveFailures = 0
    private var openedAt = 0L
    private var generation = 0L
    private var nextPermitId = 0L
    private var halfOpenPermitId: Long? = null

    val state: State
        @Synchronized get() = when {
            consecutiveFailures < failureThreshold -> State.CLOSED
            clock() - openedAt >= cooldownMs -> State.HALF_OPEN
            else -> State.OPEN
        }

    /** Acquires an attempt permit, refusing a second caller while a half-open probe is live. */
    @Synchronized
    fun acquire(): Permit? = when (currentState()) {
        State.CLOSED -> Permit(generation, null)
        State.OPEN -> null
        State.HALF_OPEN -> {
            if (halfOpenPermitId != null) null else {
                val id = ++nextPermitId
                halfOpenPermitId = id
                Permit(generation, id)
            }
        }
    }

    /** Observes whether an attempt could acquire a permit without claiming one. */
    @Synchronized
    fun allowRequest(): Boolean = when (currentState()) {
        State.CLOSED -> true
        State.OPEN, State.HALF_OPEN -> false
    }

    /** Records a successful un-tokened call, such as test setup. */
    @Synchronized
    fun recordSuccess() = closeCircuit()

    /** Records a failed un-tokened call, such as test setup. */
    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) openCircuit()
    }

    /** Invalidates all outstanding permits and returns the breaker to normal operation. */
    @Synchronized
    fun reset() = closeCircuit()

    /** A permit can settle one provider outcome or be abandoned by cancellation. */
    internal inner class Permit internal constructor(
        private val permitGeneration: Long,
        private val halfOpenId: Long?,
    ) {
        private var settled = false

        fun recordSuccess() = settle(success = true)

        fun recordFailure() = settle(success = false)

        fun abandon() {
            synchronized(this) {
                if (settled) return
                settled = true
                abandonPermit(permitGeneration, halfOpenId)
            }
        }

        private fun settle(success: Boolean) {
            synchronized(this) {
                if (settled) return
                settled = true
                settlePermit(permitGeneration, halfOpenId, success)
            }
        }
    }

    @Synchronized
    private fun settlePermit(permitGeneration: Long, halfOpenId: Long?, success: Boolean) {
        if (permitGeneration != generation || (halfOpenId != null && halfOpenId != halfOpenPermitId)) return
        if (success) {
            if (halfOpenId != null) {
                closeCircuit()
            } else {
                consecutiveFailures = 0
                openedAt = 0L
            }
        } else if (halfOpenId != null) {
            consecutiveFailures = failureThreshold
            openCircuit()
        } else {
            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold) openCircuit()
        }
    }

    @Synchronized
    private fun abandonPermit(permitGeneration: Long, halfOpenId: Long?) {
        if (permitGeneration == generation && halfOpenId != null && halfOpenId == halfOpenPermitId) {
            halfOpenPermitId = null
        }
    }

    private fun currentState(): State = when {
        consecutiveFailures < failureThreshold -> State.CLOSED
        clock() - openedAt >= cooldownMs -> State.HALF_OPEN
        else -> State.OPEN
    }

    private fun closeCircuit() {
        consecutiveFailures = 0
        openedAt = 0L
        halfOpenPermitId = null
        generation++
    }

    private fun openCircuit() {
        openedAt = clock()
        halfOpenPermitId = null
        generation++
    }

    enum class State { CLOSED, HALF_OPEN, OPEN }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
