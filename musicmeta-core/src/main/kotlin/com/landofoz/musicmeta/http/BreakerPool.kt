package com.landofoz.musicmeta.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Circuit breakers created on demand, one per key, shared by every chain in a registry.
 *
 * The key is what the arms of this probe vary; the pool itself is identical on every arm.
 */
internal class BreakerPool(seed: Map<String, CircuitBreaker> = emptyMap()) {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>(seed)

    /** Probe instrumentation: main-host walks a chain refused because this pool's key was open. */
    val mainHostSkips = AtomicInteger(0)

    fun get(key: String): CircuitBreaker = breakers.computeIfAbsent(key) { CircuitBreaker() }

    fun states(): Map<String, CircuitBreaker.State> =
        breakers.mapValues { it.value.state }.toSortedMap()

    /** Probe instrumentation: how many times each key went from below the threshold to at it. */
    fun openings(): Map<String, Int> = breakers.mapValues { it.value.openings }.toSortedMap()

    /** Probe instrumentation: the highest consecutive-failure count each key ever held. */
    fun peaks(): Map<String, Int> =
        breakers.mapValues { it.value.peakConsecutiveFailures }.toSortedMap()
}
