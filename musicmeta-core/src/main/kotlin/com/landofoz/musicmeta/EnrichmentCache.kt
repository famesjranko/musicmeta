package com.landofoz.musicmeta

/**
 * A cached [result] paired with the [canonicalStatus] the live call reported when it was written.
 * Historical evidence only: a cache hit never replays it as the current call's status, since a
 * status earned under a past engine configuration cannot speak for this call's. An all-cache-hit
 * call reports [CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT], or
 * [CanonicalStatus.NOT_ATTEMPTED_DISABLED] if the engine has identity resolution turned off —
 * either way from this call's own configuration, never from what is stored here.
 */
data class CacheEnvelope<out T : EnrichmentResult>(
    val result: T,
    val canonicalStatus: CanonicalStatus,
)

/**
 * Stores enrichment results for reuse across sessions.
 * Implementations may be in-memory (LRU), Room-backed, or custom.
 *
 * Every implementation's obligations here are asserted once, for every backend, by
 * `com.landofoz.musicmeta.contract.EnrichmentCacheContract`.
 */
interface EnrichmentCache {

    suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>?

    /**
     * Returns a cached result even if expired. Used by STALE_IF_ERROR mode
     * to serve stale data when providers fail. Return null if this implementation
     * has no notion of expiry, or does not support stale serving.
     */
    suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>?

    /**
     * [canonicalStatus] is the call's [IdentityResolution.status] that made [result] eligible to
     * cache — [EnrichmentEngine.enrich] only calls this for a status the cache may serve back with
     * no loss of confidence. [EnrichmentResult.Success.provenance] on [result] is what a hit later
     * replays; see [CacheEnvelope.canonicalStatus] for what a later [get] does with the value
     * stored here.
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
     * through the `Success`-typed positive path. Return null if this implementation does not
     * negative-cache. There is deliberately no expired-read counterpart to [getIncludingExpired]:
     * an expired negative must never be served, stale or otherwise, so STALE_IF_ERROR cannot
     * resurrect an absence a provider might since have started answering. A cache that delegates
     * to another [EnrichmentCache] must forward this call and [putNegative], or negative caching
     * silently disappears through it.
     */
    suspend fun getNegative(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.NotFound>?

    /**
     * Caches a "providers had nothing" answer for [ttlMs]. A no-op body is legal if this
     * implementation does not negative-cache, pairing with [getNegative]'s null in that case. An
     * override that does store must also clear negative entries from [invalidate] and [clear] — a
     * negative entry that outlives an invalidation would keep reporting an absence a caller just
     * asked to forget. See [getNegative] for the same forwarding obligation on a delegating cache.
     */
    suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    )

    /** Clears positive, negative, and manual-selection state for the addressed key and type(s). */
    suspend fun invalidate(entityKey: String, type: EnrichmentType? = null)

    /**
     * Whether [markManuallySelected] has been called for this key and type and not since cleared.
     * A selection is state about the key, not about a stored entry, so a key carrying no cached
     * result may still answer `true`.
     */
    suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean

    /**
     * Records that a caller chose this key and type's data itself, so an implementation preserving
     * selections does not overwrite it automatically. **A selection may be marked before anything
     * is stored for the key** — a caller can choose from candidates it has not cached — so an
     * implementation holding the marker on the cached row must still record one when no row exists.
     */
    suspend fun markManuallySelected(entityKey: String, type: EnrichmentType)

    suspend fun clear()

    companion object {
        const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
