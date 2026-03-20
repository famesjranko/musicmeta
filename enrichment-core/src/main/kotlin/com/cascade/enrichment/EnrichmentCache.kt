package com.cascade.enrichment

/**
 * Stores enrichment results for reuse across sessions.
 * Implementations may be in-memory (LRU), Room-backed, or custom.
 */
interface EnrichmentCache {

    suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success?

    suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        ttlMs: Long = DEFAULT_TTL_MS,
    )

    suspend fun invalidate(entityKey: String, type: EnrichmentType? = null)

    suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean

    suspend fun markManuallySelected(entityKey: String, type: EnrichmentType)

    suspend fun clear()

    companion object {
        const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
