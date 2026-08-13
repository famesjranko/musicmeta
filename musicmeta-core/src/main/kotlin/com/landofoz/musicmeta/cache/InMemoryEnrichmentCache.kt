package com.landofoz.musicmeta.cache

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryEnrichmentCache(
    private val maxEntries: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) : EnrichmentCache {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true)

    // Kept apart from `entries` so no Success-typed read can ever see a NotFound.
    private val negativeEntries = LinkedHashMap<String, NegativeEntry>(maxEntries, 0.75f, true)
    private val manualSelections = mutableSetOf<String>()

    override suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? = mutex.withLock {
        val key = cacheKey(entityKey, type)
        val entry = entries[key] ?: return null
        if (clock() > entry.expiresAt) { return null }
        entry.result
    }

    override suspend fun getIncludingExpired(
        entityKey: String,
        type: EnrichmentType,
    ): EnrichmentResult.Success? = mutex.withLock {
        entries[cacheKey(entityKey, type)]?.result
    }

    // canonicalStatus is not stored: this cache holds the live Success object by reference, which
    // already carries its own provenance — nothing here needs the write-time call's status back.
    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        mutex.withLock {
            entries[cacheKey(entityKey, type)] = CacheEntry(result, clock() + ttlMs)
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
    }

    override suspend fun getNegative(entityKey: String, type: EnrichmentType): EnrichmentResult.NotFound? =
        mutex.withLock {
            val key = cacheKey(entityKey, type)
            val entry = negativeEntries[key] ?: return null
            if (clock() > entry.expiresAt) { return null }
            entry.result
        }

    override suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        mutex.withLock {
            negativeEntries[cacheKey(entityKey, type)] = NegativeEntry(result, clock() + ttlMs)
            while (negativeEntries.size > maxEntries) negativeEntries.remove(negativeEntries.keys.first())
        }
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        mutex.withLock {
            if (type != null) {
                entries.remove(cacheKey(entityKey, type))
                negativeEntries.remove(cacheKey(entityKey, type))
            } else {
                entries.keys.removeAll { it.startsWith("$entityKey:") }
                negativeEntries.keys.removeAll { it.startsWith("$entityKey:") }
            }
        }
    }

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean = mutex.withLock {
        cacheKey(entityKey, type) in manualSelections
    }

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        mutex.withLock { manualSelections.add(cacheKey(entityKey, type)) }
    }

    override suspend fun clear() {
        mutex.withLock { entries.clear(); negativeEntries.clear(); manualSelections.clear() }
    }

    private fun cacheKey(entityKey: String, type: EnrichmentType) = "$entityKey:$type"
    private data class CacheEntry(val result: EnrichmentResult.Success, val expiresAt: Long)
    private data class NegativeEntry(val result: EnrichmentResult.NotFound, val expiresAt: Long)
}
