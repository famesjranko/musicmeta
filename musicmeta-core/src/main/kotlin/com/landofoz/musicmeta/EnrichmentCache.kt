package com.landofoz.musicmeta

/**
 * Stores enrichment results for reuse across sessions.
 * Implementations may be in-memory (LRU), Room-backed, or custom.
 */
interface EnrichmentCache {

    suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success?

    /**
     * Returns a cached result even if expired. Used by STALE_IF_ERROR mode
     * to serve stale data when providers fail.
     *
     * Default returns null — custom implementations that don't support
     * stale serving remain backward compatible.
     */
    suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? = null

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
