package com.cascade.enrichment.cache

import com.cascade.enrichment.EnrichmentCache
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryEnrichmentCache(
    private val maxEntries: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) : EnrichmentCache {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true)
    private val manualSelections = mutableSetOf<String>()

    override suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? = mutex.withLock {
        val key = cacheKey(entityKey, type)
        val entry = entries[key] ?: return null
        if (clock() > entry.expiresAt) { entries.remove(key); return null }
        entry.result
    }

    override suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, ttlMs: Long) {
        mutex.withLock {
            entries[cacheKey(entityKey, type)] = CacheEntry(result, clock() + ttlMs)
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        mutex.withLock {
            if (type != null) entries.remove(cacheKey(entityKey, type))
            else entries.keys.removeAll { it.startsWith("$entityKey:") }
        }
    }

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean = mutex.withLock {
        cacheKey(entityKey, type) in manualSelections
    }

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        mutex.withLock { manualSelections.add(cacheKey(entityKey, type)) }
    }

    override suspend fun clear() { mutex.withLock { entries.clear(); manualSelections.clear() } }

    private fun cacheKey(entityKey: String, type: EnrichmentType) = "$entityKey:$type"
    private data class CacheEntry(val result: EnrichmentResult.Success, val expiresAt: Long)
}
