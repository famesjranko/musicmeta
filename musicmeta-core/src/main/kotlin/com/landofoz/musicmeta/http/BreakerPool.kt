package com.landofoz.musicmeta.http

import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit breakers created on demand, one per key, shared by every chain in a registry.
 *
 * The key is what the arms of this probe vary; the pool itself is identical on every arm.
 */
internal class BreakerPool(seed: Map<String, CircuitBreaker> = emptyMap()) {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>(seed)

    fun get(key: String): CircuitBreaker = breakers.computeIfAbsent(key) { CircuitBreaker() }

    fun states(): Map<String, CircuitBreaker.State> =
        breakers.mapValues { it.value.state }.toSortedMap()
}
