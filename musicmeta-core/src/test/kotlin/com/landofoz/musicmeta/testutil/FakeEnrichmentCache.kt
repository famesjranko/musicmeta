package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
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

    /**
     * [CanonicalStatus] each [stored]/[expiredStore] entry was written under, by the same key. A
     * key seeded directly into [stored] rather than through [put] has no entry here, so [get]/
     * [getIncludingExpired] fall back to [CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT] — a status that
     * claims nothing, matching a cache that never learned to preserve one either.
     */
    val storedStatuses = mutableMapOf<String, CanonicalStatus>()
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

    override suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? {
        failIfRequested(CacheOp.GET, entityKey)
        val key = "$entityKey:$type"
        return stored[key]?.let { CacheEnvelope(it, storedStatuses[key] ?: CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT) }
    }
    override suspend fun getIncludingExpired(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.Success>? {
        failIfRequested(CacheOp.GET_INCLUDING_EXPIRED, entityKey)
        val key = "$entityKey:$type"
        val result = stored[key] ?: expiredStore[key] ?: return null
        return CacheEnvelope(result, storedStatuses[key] ?: CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT)
    }
    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        failIfRequested(CacheOp.PUT, entityKey)
        val key = "$entityKey:$type"
        stored[key] = result; storedTtls[key] = ttlMs; storedStatuses[key] = canonicalStatus
    }
    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        failIfRequested(CacheOp.INVALIDATE, entityKey)
        if (type != null) {
            val key = "$entityKey:$type"
            stored.remove(key)
            expiredStore.remove(key)
            storedTtls.remove(key)
            storedStatuses.remove(key)
            manualSelections.remove(key)
        } else {
            val prefix = "$entityKey:"
            stored.keys.removeAll { it.startsWith(prefix) }
            expiredStore.keys.removeAll { it.startsWith(prefix) }
            storedTtls.keys.removeAll { it.startsWith(prefix) }
            storedStatuses.keys.removeAll { it.startsWith(prefix) }
            manualSelections.removeAll { it.startsWith(prefix) }
        }
    }
    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) = "$entityKey:$type" in manualSelections
    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) { manualSelections.add("$entityKey:$type") }
    override suspend fun clear() { stored.clear(); expiredStore.clear(); manualSelections.clear() }
}
