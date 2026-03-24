package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

class FakeEnrichmentCache : EnrichmentCache {
    val stored = mutableMapOf<String, EnrichmentResult.Success>()
    val storedTtls = mutableMapOf<String, Long>()
    val expiredStore = mutableMapOf<String, EnrichmentResult.Success>()
    private val manualSelections = mutableSetOf<String>()

    override suspend fun get(entityKey: String, type: EnrichmentType) = stored["$entityKey:$type"]
    override suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? {
        val key = "$entityKey:$type"
        return stored[key] ?: expiredStore[key]
    }
    override suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, ttlMs: Long) { stored["$entityKey:$type"] = result; storedTtls["$entityKey:$type"] = ttlMs }
    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        if (type != null) stored.remove("$entityKey:$type") else stored.keys.removeAll { it.startsWith("$entityKey:") }
    }
    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) = "$entityKey:$type" in manualSelections
    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) { manualSelections.add("$entityKey:$type") }
    override suspend fun clear() { stored.clear(); expiredStore.clear(); manualSelections.clear() }
}
