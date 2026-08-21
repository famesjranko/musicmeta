package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.serialization.json.Json

/** Discogs' API terms forbid displaying their content more than six hours behind their own copy. */
internal const val DISCOGS_FRESHNESS_CEILING_MS = 6L * 60 * 60 * 1000

/**
 * An [EnrichmentCache] that will not serve Discogs-sourced data for longer than
 * [ceilingMs]. Wraps [delegate] rather than replacing it, so entries carrying nothing from Discogs
 * keep the TTL the engine asked for.
 *
 * Two paths reach a stored entry and both are capped: [get] through the shortened TTL written at
 * [put], and [getIncludingExpired], which `STALE_IF_ERROR` uses to serve past expiry — a ceiling
 * that only covered the first would be lifted by flipping the demo's cache mode.
 */
internal class DiscogsFreshnessCache(
    private val delegate: EnrichmentCache,
    private val ceilingMs: Long = DISCOGS_FRESHNESS_CEILING_MS,
) : EnrichmentCache {

    override suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? =
        delegate.get(entityKey, type)

    /**
     * A stale read is answered only for an entry naming no Discogs source. For one that does, the
     * unexpired read is the answer — non-null while the entry is inside the ceiling, null once it
     * is past it, which is the only way this wrapper can tell fresh from expired through an API
     * that deliberately hides the difference.
     */
    override suspend fun getIncludingExpired(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.Success>? {
        val stale = delegate.getIncludingExpired(entityKey, type) ?: return null
        if (!stale.result.namesDiscogs()) return stale
        return delegate.get(entityKey, type)
    }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) = delegate.put(
        entityKey,
        type,
        result,
        canonicalStatus,
        if (result.namesDiscogs()) minOf(ttlMs, ceilingMs) else ttlMs,
    )

    override suspend fun getNegative(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.NotFound>? = delegate.getNegative(entityKey, type)

    override suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) = delegate.putNegative(entityKey, type, result, canonicalStatus, ttlMs)

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) =
        delegate.invalidate(entityKey, type)

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean =
        delegate.isManuallySelected(entityKey, type)

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) =
        delegate.markManuallySelected(entityKey, type)

    override suspend fun clear() = delegate.clear()
}

private val attributionScan = Json { encodeDefaults = false }

/**
 * Whether anything in this result could be Discogs content. Reads the serialised payload rather
 * than a list of the fields that carry a source today — `sources`, `alternatives`, a popularity
 * signal's `source` and a flat Discogs rating are four different carriers already, and a fifth
 * added upstream would extend a hand-written list silently.
 *
 * Deliberately over-broad: a payload that merely links to Discogs is capped too, which costs a
 * refetch, where missing one costs a term of the licence.
 */
internal fun EnrichmentResult.Success.namesDiscogs(): Boolean =
    provider.contains(DISCOGS_ID, ignoreCase = true) ||
        attributionScan.encodeToString(EnrichmentData.serializer(), data).contains(DISCOGS_ID, ignoreCase = true)
