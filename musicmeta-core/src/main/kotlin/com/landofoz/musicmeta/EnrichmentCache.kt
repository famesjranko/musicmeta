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

    /**
     * [canonicalStatus] is the call's [IdentityResolution.status] that made [result] eligible to
     * cache — [EnrichmentEngine.enrich] only calls this for a status the cache may serve back with
     * no loss of confidence. A hit later replays it verbatim; it must never be reported as
     * [CanonicalStatus.RESOLVED] merely because the entry is present.
     */
    suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long = DEFAULT_TTL_MS,
    )

    /**
     * Returns a cached "providers had nothing" answer, or null on a miss or expiry.
     *
     * Stored apart from [get]/[put] on purpose: a [EnrichmentResult.NotFound] must never flow
     * through the `Success`-typed positive path. Default returns null, so an implementation that
     * does not override this pair simply never negative-caches — existing implementations compile
     * and behave unchanged. There is deliberately no expired-read counterpart to
     * [getIncludingExpired]: an expired negative must never be served, stale or otherwise, so
     * STALE_IF_ERROR cannot resurrect an absence a provider might since have started answering.
     * A cache that delegates to another [EnrichmentCache] must forward this call and
     * [putNegative], or negative caching silently disappears through it.
     */
    suspend fun getNegative(entityKey: String, type: EnrichmentType): EnrichmentResult.NotFound? = null

    /**
     * Caches a "providers had nothing" answer for [ttlMs]. Default is a no-op, pairing with
     * [getNegative]'s default of null. An override must also clear negative entries from
     * [invalidate] and [clear] — a negative entry that outlives an invalidation would keep
     * reporting an absence a caller just asked to forget. A delegating cache must forward this
     * call and [getNegative], or negative caching silently disappears through it.
     */
    suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {}

    suspend fun invalidate(entityKey: String, type: EnrichmentType? = null)

    suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean

    suspend fun markManuallySelected(entityKey: String, type: EnrichmentType)

    suspend fun clear()

    companion object {
        const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
