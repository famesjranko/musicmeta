package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.cache.CacheMode

/**
 * Every read from and write to the [EnrichmentCache] a call makes, and the rules that decide which
 * of them happen: what a cache hit may answer, which key an answer is aliased under, what is safe
 * to persist under this call's canonical status, and what an expired entry may still stand in for.
 *
 * Held by [DefaultEnrichmentEngine], which owns the fan-out and the per-type finalization but
 * delegates the cache decisions here — the engine asks a provider chain a question, this answers
 * whether the question needed asking and whether the answer survives the call.
 */
internal class CachePersistence(
    private val cache: EnrichmentCache,
    private val config: EnrichmentConfig,
    private val logger: EnrichmentLogger,
) {

    /** [runProgressiveFanOut]'s cache-read pass: cache hits, negative-cache hits, and what remains uncached. */
    suspend fun readCacheLayer(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): CacheLayer {
        val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
        val uncachedTypes = mutableSetOf<EnrichmentType>()
        val negativeCacheHits = mutableSetOf<EnrichmentType>()
        for (type in types) {
            val cached = if (forceRefresh) {
                null
            } else {
                guardedCacheRead(logger, "get") { cache.get(entityKeyFor(request, type), type) }
            }
            // A cached Success answering nothing is a *miss*, not a NotFound. An empty entry written
            // by an older build would otherwise outlive this fix by the type's TTL — 90 days for
            // GENRE — re-demoted on every call and never refetched. Leaving the type uncached lets
            // the providers run and the write-back overwrite it, so the entry heals itself.
            // An entry whose genre tags never learned whether they were curated takes the same route
            // for the same reason: see hasUnknownGenreCuration.
            if (cached != null &&
                cached.result.data.answers(type) &&
                !cached.result.data.hasUnknownGenreCuration(type)
            ) {
                results[type] = withCacheProvenanceFallback(cached.result)
                continue
            }
            // A fresh negative entry answers "providers had nothing" without a re-ask; the read is
            // skipped under forceRefresh for the same reason as the positive read above.
            val negative = if (forceRefresh) {
                null
            } else {
                guardedCacheRead(logger, "getNegative") { cache.getNegative(entityKeyFor(request, type), type) }
            }
            if (negative != null) {
                results[type] = negative.result
                negativeCacheHits.add(type)
            } else {
                uncachedTypes.add(type)
            }
        }
        return CacheLayer(results, uncachedTypes, negativeCacheHits)
    }

    suspend fun writeBack(
        request: EnrichmentRequest,
        resolvedRequest: EnrichmentRequest,
        results: Map<EnrichmentType, EnrichmentResult>,
        context: WriteBackContext,
    ) {
        val resolvedMbid = context.identityResolution.identifiers.musicBrainzId
        val canonicalStatus = context.identityResolution.status
        for ((type, result) in results) {
            val aliasKey = aliasKeyFor(request, resolvedRequest, resolvedMbid, type)
            val identifierIncomplete = context.chainExecutions[type]?.identifierIncomplete == true
            val filterEmptied = type in context.filterEmptied
            val staleDerived = type in context.staleDerived
            val cacheable =
                isCacheableNegative(result, canonicalStatus, identifierIncomplete, filterEmptied, staleDerived)
            when {
                // A negative served from cache this call is not re-put: its short TTL is the entry's
                // freshness contract, and a cache hit must not extend it.
                cacheable && type !in context.negativeCacheHits ->
                    writeNegative(request, aliasKey, type, result as EnrichmentResult.NotFound, canonicalStatus)
                isCacheablePositive(result, canonicalStatus) ->
                    writePositive(request, aliasKey, type, result as EnrichmentResult.Success, canonicalStatus)
            }
        }
    }

    /**
     * The name-alias key when identity resolution added an MBID, so a future name-only lookup
     * finds MBID-resolved data — shared by both write branches below, so a negative write ends up
     * under exactly the same keys a Success would. Force refresh and [invalidateKeys] clear that
     * alias once identity resolution has recovered its canonical names. A request that named no
     * entity has no caller name to alias under, so it takes
     * MusicBrainz's canonical one — the same name a later name-only lookup would ask with.
     *
     * Never fires for a request carrying caller-supplied identifiers: a caller name is not an
     * equivalence proof for those identifiers. The only identifier-bearing alias is the canonical
     * name learned during actual identity resolution.
     */
    private fun aliasKeyFor(
        request: EnrichmentRequest,
        resolvedRequest: EnrichmentRequest,
        resolvedMbid: String?,
        type: EnrichmentType,
    ): String? = when {
        namesNoEntity(request) && !namesNoEntity(resolvedRequest) -> entityKeyForName(resolvedRequest, type)
        resolvedMbid != null && request.identifiers.musicBrainzId == null &&
            entityKeyFor(request, type) == entityKeyForName(request, type) -> entityKeyForName(request, type)
        else -> null
    }

    /**
     * Whether a result reached under this call's canonical status is safe to cache: [CanonicalStatus.RESOLVED]
     * or any `NOT_ATTEMPTED_*` reason — never [CanonicalStatus.AMBIGUOUS], [CanonicalStatus.UNRESOLVED],
     * [CanonicalStatus.FAILED], or [CanonicalStatus.RESOLVING], each of which means this call's fan-out
     * ran (or is still running) on an unconfirmed identity.
     */
    private fun CanonicalStatus.isCacheable(): Boolean = this !in UNCACHEABLE_STATUSES

    /**
     * Only a real fan-out "providers had nothing" qualifies for negative caching: never a chain
     * that skipped a provider for an identifier this call never had ([identifierIncomplete]), never
     * one [DefaultEnrichmentEngine.finalizeResult] produced by catalog-filtering a `Success` down
     * to nothing ([filterEmptied]) — that emptiness describes the local catalog, not an upstream
     * provider, whether the `Success` it emptied was this call's own live answer or a stale-cache
     * substitute — never one whose finalized value is itself a stale-cache substitute, or a
     * composite synthesized from one ([staleDerived]), since a stale substitute is a past call's
     * snapshot rather than this call's own answer, and never one reached under a canonical identity
     * that did not resolve. Decided from the call's own [canonicalStatus] and those three per-type
     * facts — a `NotFound` carries no per-result canonical fact of its own, so it is never consulted
     * here.
     */
    fun isCacheableNegative(
        result: EnrichmentResult,
        canonicalStatus: CanonicalStatus,
        identifierIncomplete: Boolean,
        filterEmptied: Boolean,
        staleDerived: Boolean,
    ): Boolean =
        result is EnrichmentResult.NotFound &&
            !identifierIncomplete &&
            !filterEmptied &&
            !staleDerived &&
            canonicalStatus.isCacheable()

    /**
     * A `Success` reached while canonical resolution was attempted and did not resolve
     * (`AMBIGUOUS`/`UNRESOLVED`/`FAILED`) is a fuzzy or ambiguous guess — caching it would serve it
     * as a cache hit for the type's whole TTL with no way to tell it apart from a confident one,
     * and a retry could never heal or re-offer the suggestions that produced it.
     */
    private fun isCacheablePositive(result: EnrichmentResult, canonicalStatus: CanonicalStatus): Boolean =
        result is EnrichmentResult.Success && !result.isStale && canonicalStatus.isCacheable()

    private suspend fun writeNegative(
        request: EnrichmentRequest,
        aliasKey: String?,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
    ) {
        guardedCacheWrite(logger, "putNegative") {
            cache.putNegative(entityKeyFor(request, type), type, result, canonicalStatus, config.negativeTtlMs)
        }
        if (aliasKey != null) {
            guardedCacheWrite(logger, "putNegative") {
                cache.putNegative(aliasKey, type, result, canonicalStatus, config.negativeTtlMs)
            }
        }
    }

    private suspend fun writePositive(
        request: EnrichmentRequest,
        aliasKey: String?,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
    ) {
        val ttl = config.ttlOverrides[type] ?: type.defaultTtlMs
        guardedCacheWrite(logger, "put") {
            cache.put(entityKeyFor(request, type), type, result, canonicalStatus, ttl)
        }
        if (aliasKey != null) {
            guardedCacheWrite(logger, "put") { cache.put(aliasKey, type, result, canonicalStatus, ttl) }
        }
    }

    suspend fun invalidateForRefresh(request: EnrichmentRequest, types: Set<EnrichmentType>) {
        for (type in types) {
            for (key in cacheKeysFor(request, type)) {
                guardedCacheWrite(logger, "invalidate") { cache.invalidate(key, type) }
            }
        }
    }

    /**
     * The canonical-name alias a forced call could not clear on the way in: the request named no
     * entity then, and the name the alias sits under is the one identity resolution has just
     * learned.
     */
    suspend fun invalidateResolvedNameAlias(resolvedRequest: EnrichmentRequest, types: Set<EnrichmentType>) {
        for (type in types) {
            guardedCacheWrite(logger, "invalidate") {
                cache.invalidate(entityKeyForName(resolvedRequest, type), type)
            }
        }
    }

    /**
     * Exact-bearing requests invalidate only their complete primary tuple. A caller-supplied name
     * is not an equivalence proof, so clearing its bare-name key could evict another entity's
     * answer. Canonical aliases are added by [invalidateKeys] only after identity resolution has
     * supplied the canonical names.
     */
    private fun cacheKeysFor(request: EnrichmentRequest, type: EnrichmentType): List<String> =
        listOf(entityKeyFor(request, type))

    /** Invalidates the primary tuple and a canonical-name alias only when resolution supplied it. */
    suspend fun invalidateKeys(
        request: EnrichmentRequest,
        named: EnrichmentRequest,
        type: EnrichmentType,
    ) {
        val keys = cacheKeysFor(request, type) +
            if (named !== request) listOf(entityKeyForName(named, type)) else emptyList()
        for (key in keys.distinct()) cache.invalidate(key, type)
    }

    /**
     * A cache hit reports [LookupProvenance.CACHE] instead of `null` when the [EnrichmentCache]
     * implementation that served it did not preserve the original live lookup's route — never
     * `null`, or a consumer reading absence as confident inherits the same hole [CanonicalStatus]
     * closed for canonical resolution. A preserving cache (both shipped implementations) replays
     * the original route verbatim and never reaches this branch; it exists for one that does not.
     */
    private fun withCacheProvenanceFallback(result: EnrichmentResult.Success): EnrichmentResult.Success =
        if (result.provenance == null) result.copy(provenance = LookupProvenance.CACHE) else result

    /**
     * [config.cacheMode]'s `STALE_IF_ERROR` clause for one type: a fresh `Error`/`RateLimited` is
     * replaced by an expired cache entry that still answers the type, marked [EnrichmentResult.Success.isStale].
     * Any other result — including a fresh `Success` or `NotFound` — passes through unchanged.
     *
     * [ErrorKind.ENGINE_CLOSED] is never substituted, even under `STALE_IF_ERROR`: it is the one
     * `Error` this engine stamps itself, after [DefaultEnrichmentEngine.close] — that method's own
     * KDoc promises every unsettled requested type becomes that Error, unconditionally, and a stale
     * cache hit silently standing in for it would break that promise for exactly the caller relying
     * on it to notice a shutdown. [ErrorKind.TIMEOUT], stamped the same way for a different reason,
     * keeps the normal substitution.
     */
    suspend fun applyStaleCacheToType(
        request: EnrichmentRequest,
        type: EnrichmentType,
        result: EnrichmentResult,
    ): EnrichmentResult {
        if (config.cacheMode != CacheMode.STALE_IF_ERROR) return result
        if (result !is EnrichmentResult.Error && result !is EnrichmentResult.RateLimited) return result
        if (result is EnrichmentResult.Error && result.errorKind == ErrorKind.ENGINE_CLOSED) return result
        val stale = guardedCacheRead(logger, "getIncludingExpired") {
            cache.getIncludingExpired(entityKeyFor(request, type), type)
        }
        // A stale entry that answers nothing is worse than the Error it would replace: the Error at
        // least tells the consumer to retry.
        return if (stale != null && stale.result.data.answers(type)) {
            withCacheProvenanceFallback(stale.result).copy(isStale = true)
        } else {
            result
        }
    }

    private companion object {
        // RESOLVING never actually reaches isCacheable(): writeBack only runs with the real,
        // settled session.identityResolution. Listed anyway so a future caller of isCacheable()
        // against a live IdentityHolder.current can't accidentally treat an in-progress
        // resolution as safe to cache.
        private val UNCACHEABLE_STATUSES = setOf(
            CanonicalStatus.AMBIGUOUS, CanonicalStatus.UNRESOLVED, CanonicalStatus.FAILED,
            CanonicalStatus.RESOLVING,
        )
    }
}
