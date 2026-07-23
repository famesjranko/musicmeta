package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/** A cache operation that [FakeEnrichmentCache] can be told to fail, to exercise cache-failure paths. */
enum class CacheOp { GET, GET_INCLUDING_EXPIRED, PUT, INVALIDATE }

// `open`, like FakeProvider: a test that needs one operation to misbehave in a way `failing` cannot
// express — suspending, or raising a CancellationException — subclasses and overrides that one.
open class FakeEnrichmentCache : EnrichmentCache {
    val stored = mutableMapOf<String, EnrichmentResult.Success>()
    val storedTtls = mutableMapOf<String, Long>()
    val expiredStore = mutableMapOf<String, EnrichmentResult.Success>()
    private val manualSelections = mutableSetOf<String>()

    /** Operations that throw instead of running. Empty by default, so existing tests are unaffected. */
    var failing: Set<CacheOp> = emptySet()

    /** When set, only operations on this exact entity key fail; otherwise every key fails. */
    var failingKey: String? = null

    private fun failIfRequested(op: CacheOp, entityKey: String) {
        if (op !in failing) return
        if (failingKey != null && failingKey != entityKey) return
        throw IllegalStateException("simulated cache failure: $op on $entityKey")
    }

    override suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? {
        failIfRequested(CacheOp.GET, entityKey)
        return stored["$entityKey:$type"]
    }
    override suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? {
        failIfRequested(CacheOp.GET_INCLUDING_EXPIRED, entityKey)
        val key = "$entityKey:$type"
        return stored[key] ?: expiredStore[key]
    }
    override suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, ttlMs: Long) {
        failIfRequested(CacheOp.PUT, entityKey)
        stored["$entityKey:$type"] = result; storedTtls["$entityKey:$type"] = ttlMs
    }
    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        failIfRequested(CacheOp.INVALIDATE, entityKey)
        if (type != null) stored.remove("$entityKey:$type") else stored.keys.removeAll { it.startsWith("$entityKey:") }
    }
    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) = "$entityKey:$type" in manualSelections
    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) { manualSelections.add("$entityKey:$type") }
    override suspend fun clear() { stored.clear(); expiredStore.clear(); manualSelections.clear() }
}
