package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.http.EnrichDeadline
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class DefaultEnrichmentEngine(
    private val registry: ProviderRegistry,
    override val cache: EnrichmentCache,
    internal val config: EnrichmentConfig,
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
    mergers: List<ResultMerger> = listOf(GenreMerger),
    synthesizers: List<CompositeSynthesizer> = listOf(TimelineSynthesizer),
) : EnrichmentEngine {

    private val mergers: Map<EnrichmentType, ResultMerger> = mergers.associateBy { it.type }
    private val synthesizers: Map<EnrichmentType, CompositeSynthesizer> = synthesizers.associateBy { it.type }

    private val mergeableTypes: Set<EnrichmentType> get() = mergers.keys
    private val compositeDependencies: Map<EnrichmentType, Set<EnrichmentType>>
        get() = synthesizers.mapValues { it.value.dependencies }

    override suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): EnrichmentResults {
        if (forceRefresh) {
            // Guarded per key, not per type: a failure on the primary key must not skip the alias.
            for (type in types) {
                for (key in cacheKeysFor(request, type)) {
                    guardedCacheWrite(logger, "invalidate") { cache.invalidate(key, type) }
                }
            }
        }

        val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
        val uncachedTypes = mutableSetOf<EnrichmentType>()
        val negativeCacheHits = mutableSetOf<EnrichmentType>()

        for (type in types) {
            // forceRefresh already invalidated these keys; skipping the read keeps a *failed*
            // invalidation from resurrecting the stale entry it was meant to drop.
            val cached = if (forceRefresh) null else {
                guardedCacheRead(logger, "get") { cache.get(entityKeyFor(request, type), type) }
            }
            // A cached Success answering nothing is a *miss*, not a NotFound. An empty entry written
            // by an older build would otherwise outlive this fix by the type's TTL — 90 days for
            // GENRE — re-demoted on every call and never refetched. Leaving the type uncached lets
            // the providers run and the write-back overwrite it, so the entry heals itself.
            // An entry whose genre tags never learned whether they were curated takes the same route
            // for the same reason: see hasUnknownGenreCuration.
            if (cached != null && cached.data.answers(type) && !cached.data.hasUnknownGenreCuration()) {
                results[type] = cached
                continue
            }
            // A fresh negative entry answers "providers had nothing" without a re-ask; the read is
            // skipped under forceRefresh for the same reason as the positive read above.
            val negative = if (forceRefresh) null else {
                guardedCacheRead(logger, "getNegative") { cache.getNegative(entityKeyFor(request, type), type) }
            }
            if (negative != null) {
                results[type] = negative
                negativeCacheHits.add(type)
            } else {
                uncachedTypes.add(type)
            }
        }
        if (uncachedTypes.isEmpty()) {
            return EnrichmentResults(results, types, identity = null)
        }

        var identityResolution: IdentityResolution? = null
        // The request as identity resolution left it — the names it backfilled are what the
        // write-back aliases an MBID-only result under.
        var resolvedRequest: EnrichmentRequest = request

        // withTimeoutOrNull returns null only when *this* deadline expired. A nested withTimeout's
        // expiry — a consumer's CatalogProvider, say — propagates instead of being caught by type
        // and mislabelled as enrichTimeoutMs. (#55)
        // EnrichDeadline carries the budget down to DefaultHttpClient, so a 429 retry can decline to
        // sleep past this deadline — an expiry mid-fan-out loses every provider's in-flight work.
        val completed = withTimeoutOrNull(config.enrichTimeoutMs) {
            // TransientIdentifierMarker: this call's record of which IdentifierRequirements a
            // transient left unresolved this run, read back by reclassifyTransientGap.
            // ProviderCallScope: this call's home for whatever a provider memoizes across the types
            // of one request, so nothing it holds can survive to answer the next call.
            withContext(
                EnrichDeadline(config.enrichTimeoutMs) + TransientIdentifierMarker() +
                    ProviderCallScope() + ResolvedEntityNames(),
            ) {
                var identityResult: EnrichmentResult? = null
                val enrichedRequest = if (config.enableIdentityResolution &&
                    needsIdentityResolution(request, uncachedTypes, registry)) {
                    resolveIdentity(request, results, uncachedTypes).also { identityResult = it.second }.first
                } else request

                resolvedRequest = enrichedRequest
                // The canonical-name alias could not be invalidated above: the request named no
                // entity then, and the name it is aliased under is the one resolution just learned.
                if (forceRefresh && namesNoEntity(request) && !namesNoEntity(enrichedRequest)) {
                    for (type in uncachedTypes) {
                        guardedCacheWrite(logger, "invalidate") {
                            cache.invalidate(entityKeyForName(enrichedRequest, type), type)
                        }
                    }
                }
                // The same channel the name backfill reads, so the canonical names a consumer is
                // handed are the ones the fan-out was built from — no second resolution path.
                identityResolution = buildIdentityResolution(
                    identityResult,
                    enrichedRequest,
                    currentCoroutineContext()[ResolvedEntityNames]?.resolved(),
                )

                // Short-circuit: when identity failed with suggestions, skip provider fan-out
                val identityNotFound = identityResult as? EnrichmentResult.NotFound
                if (identityNotFound?.suggestions != null) {
                    for (type in uncachedTypes) {
                        results[type] = EnrichmentResult.NotFound(type, "engine",
                            suggestions = identityNotFound.suggestions, identityMatch = IdentityMatch.SUGGESTIONS)
                    }
                } else {
                    results.putAll(resolveTypes(enrichedRequest, uncachedTypes, identityResult))
                    applyCatalogFiltering(results, config.catalogProvider, config.catalogFilterMode)
                    stampIdentityMatch(results, identityResult)
                }
                true
            }
        } ?: false

        if (!completed) {
            logger.warn(TAG, "Enrich timed out after ${config.enrichTimeoutMs}ms")
            for (type in types) {
                if (type !in results) {
                    results[type] =
                        EnrichmentResult.Error(type, "engine", "Enrichment timed out", errorKind = ErrorKind.TIMEOUT)
                }
            }
        }

        // Stale fallback and write-back are outside the timed block above on purpose: a timeout
        // must not discard results already fetched. Don't move these inside it.
        if (config.cacheMode == CacheMode.STALE_IF_ERROR) {
            applyStaleCache(request, results, uncachedTypes)
        }

        // A timed-out run persists nothing. The deadline can fire part-way through a step that
        // rewrites entries in `results` — catalog filtering does exactly that, per type — so what
        // survives is a mix of finished and unfinished work. Returning it is the contract; caching
        // it would outlive the call that truncated it. (#56)
        if (completed) writeBack(request, resolvedRequest, results, identityResolution, negativeCacheHits)

        return EnrichmentResults(results, types, identityResolution)
    }

    private suspend fun writeBack(
        request: EnrichmentRequest,
        resolvedRequest: EnrichmentRequest,
        results: Map<EnrichmentType, EnrichmentResult>,
        identityResolution: IdentityResolution?,
        negativeCacheHits: Set<EnrichmentType>,
    ) {
        val resolvedMbid = identityResolution?.identifiers?.musicBrainzId
        for ((type, result) in results) {
            val aliasKey = aliasKeyFor(request, resolvedRequest, resolvedMbid, type)
            when {
                // A negative served from cache this call is not re-put: its short TTL is the entry's
                // freshness contract, and a cache hit must not extend it.
                isCacheableNegative(result) && type !in negativeCacheHits ->
                    writeNegative(request, aliasKey, type, result as EnrichmentResult.NotFound)
                isCacheablePositive(result) ->
                    writePositive(request, aliasKey, type, result as EnrichmentResult.Success)
            }
        }
    }

    /**
     * The name-alias key when identity resolution added an MBID, so a future name-only lookup
     * finds MBID-resolved data — shared by both write branches below, so a negative write ends up
     * under exactly the same keys a Success would, and `cacheKeysFor`/`invalidateKeys` clear it for
     * free. A request that named no entity has no caller name to alias under, so it takes
     * MusicBrainz's canonical one — the same name a later name-only lookup would ask with.
     */
    private fun aliasKeyFor(
        request: EnrichmentRequest,
        resolvedRequest: EnrichmentRequest,
        resolvedMbid: String?,
        type: EnrichmentType,
    ): String? = when {
        namesNoEntity(request) && !namesNoEntity(resolvedRequest) -> entityKeyForName(resolvedRequest, type)
        resolvedMbid != null && request.identifiers.musicBrainzId == null -> entityKeyForName(request, type)
        else -> null
    }

    /**
     * Only a real fan-out "providers had nothing" qualifies for negative caching — never the
     * identity short-circuit's manufactured `NotFound` (`suggestions != null`) or one riding an
     * unverified identity. Keyed on suggestions/identityMatch, not provider id, since a merger's
     * fan-out `NotFound` arrives as provider `"all_providers"`.
     */
    internal fun isCacheableNegative(result: EnrichmentResult): Boolean =
        result is EnrichmentResult.NotFound && result.suggestions == null &&
            (result.identityMatch == null || result.identityMatch == IdentityMatch.RESOLVED)

    /**
     * UNVERIFIED results are fuzzy guesses fetched while the identity provider was down — caching
     * them would serve them as cache hits (identity == null, which reads as confident) for the
     * type's whole TTL, and a retry could never heal them.
     */
    private fun isCacheablePositive(result: EnrichmentResult): Boolean =
        result is EnrichmentResult.Success && !result.isStale && result.identityMatch != IdentityMatch.UNVERIFIED

    private suspend fun writeNegative(
        request: EnrichmentRequest,
        aliasKey: String?,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
    ) {
        guardedCacheWrite(logger, "putNegative") {
            cache.putNegative(entityKeyFor(request, type), type, result, config.negativeTtlMs)
        }
        if (aliasKey != null) {
            guardedCacheWrite(logger, "putNegative") { cache.putNegative(aliasKey, type, result, config.negativeTtlMs) }
        }
    }

    private suspend fun writePositive(
        request: EnrichmentRequest,
        aliasKey: String?,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
    ) {
        val ttl = config.ttlOverrides[type] ?: type.defaultTtlMs
        guardedCacheWrite(logger, "put") { cache.put(entityKeyFor(request, type), type, result, ttl) }
        if (aliasKey != null) {
            guardedCacheWrite(logger, "put") { cache.put(aliasKey, type, result, ttl) }
        }
    }

    override fun enrichBatch(
        requests: List<EnrichmentRequest>,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): Flow<Pair<EnrichmentRequest, EnrichmentResults>> = flow {
        for (request in requests) {
            emit(request to enrich(request, types, forceRefresh))
        }
    }

    override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) {
        val types = if (type != null) listOf(type) else EnrichmentType.entries
        // Resolved once for every type: an identifier-only request's alias key is the *canonical*
        // name, which nothing on the request carries, so it has to be asked for.
        val named = canonicallyNamed(request)
        for (t in types) invalidateKeys(request, named, t)
    }

    override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean =
        cache.isManuallySelected(entityKeyFor(request, type), type)

    override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) {
        cache.markManuallySelected(entityKeyFor(request, type), type)
    }

    // search() has no deadline of its own, but its providers use the same typed HTTP calls, so
    // without a budget a 429 there retries against DefaultHttpClient's 120s standalone ceiling.
    // enrichTimeoutMs is the budget a consumer already stated for a call of this kind.
    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> =
        withContext(EnrichDeadline(config.enrichTimeoutMs)) { searchCandidates(request, limit) }

    private suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
        val identity = registry.identityProvider()
        val primary = if (identity != null) {
            try {
                identity.searchCandidates(request, limit)
            } catch (e: Exception) {
                // ensureActive() throws only when *this* job is cancelled. A CancellationException
                // from elsewhere — a provider's own withTimeout — falls through and is handled as
                // the failure it is, rather than escaping to be reported as our deadline. Plain
                // `catch (CancellationException) { throw e }` gets that second case wrong. (#53)
                currentCoroutineContext().ensureActive()
                logger.warn(TAG, "Identity search failed: ${e.message}", e)
                emptyList()
            }
        } else emptyList()

        if (primary.size >= limit) return primary.take(limit)

        val remaining = limit - primary.size
        val supplemental = registry.searchProviders().flatMap { provider ->
            if (!provider.isAvailable) return@flatMap emptyList()
            try {
                provider.searchCandidates(request, remaining)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // as above
                logger.warn(TAG, "Search failed for ${provider.id}: ${e.message}", e)
                emptyList()
            }
        }

        val primaryTitles = primary.map { "${it.title}:${it.artist}".lowercase() }.toSet()
        val unique = supplemental.filter {
            "${it.title}:${it.artist}".lowercase() !in primaryTitles
        }
        return (primary + unique).take(limit)
    }

    override fun getProviders(): List<ProviderInfo> = registry.providerInfos()

    /** The probe behind [com.landofoz.musicmeta.discoverMbidEntityType], which holds its contract. */
    internal suspend fun discoverEntityType(mbid: String): MusicBrainzEntityType? {
        val musicBrainz = registry.identityProvider() as? MusicBrainzProvider
            ?: error("No MusicBrainz identity provider is registered; nothing can resolve an MBID's type")
        // An ambient scope is the caller's own call, and joining it is the whole saving: a probe
        // whose entity that call has already looked up costs nothing. Installing one unconditionally
        // would hand every probe a cold memo instead.
        return if (currentCoroutineContext()[ProviderCallScope] != null) {
            musicBrainz.discoverEntityType(mbid)
        } else {
            withContext(ProviderCallScope()) { musicBrainz.discoverEntityType(mbid) }
        }
    }

    /** The primary key, plus the name-alias key when the request carries an MBID. */
    private fun cacheKeysFor(request: EnrichmentRequest, type: EnrichmentType): List<String> =
        if (request.identifiers.musicBrainzId != null) {
            listOf(entityKeyFor(request, type), entityKeyForName(request, type))
        } else {
            listOf(entityKeyFor(request, type))
        }

    /** Invalidates the primary key and both name-alias keys — the caller's and the canonical one. */
    private suspend fun invalidateKeys(
        request: EnrichmentRequest,
        named: EnrichmentRequest,
        type: EnrichmentType,
    ) {
        val keys = cacheKeysFor(request, type) +
            if (named !== request) listOf(entityKeyForName(named, type)) else emptyList()
        for (key in keys.distinct()) cache.invalidate(key, type)
    }

    /**
     * [request] with the names identity resolution would fill in, for a request that names none —
     * the only way to reach the canonical-name alias [writeBack] left behind, since the caller's
     * own request carries no name to derive it from.
     *
     * Costs the identity lookup, and only for an identifier-only request. A failure degrades to the
     * request as given: an alias that outlives one failed invalidation is recoverable, and throwing
     * out of `invalidate()` for a transient is not.
     */
    // SwallowedException: the degrade is the contract above; the exception has no second reader.
    @Suppress("SwallowedException")
    private suspend fun canonicallyNamed(request: EnrichmentRequest): EnrichmentRequest {
        if (!namesNoEntity(request)) return request
        val provider = registry.identityProvider() ?: return request
        val names = ResolvedEntityNames()
        return try {
            withContext(ProviderCallScope() + names) { provider.resolveIdentity(request) }
            request.withBackfilledNames(names.resolved())
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(TAG, "Could not name an identifier-only request to invalidate its alias")
            request
        }
    }

    /** Returns the enriched request and the raw identity result (for composite type synthesis). */
    private suspend fun resolveIdentity(
        request: EnrichmentRequest,
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        uncachedTypes: MutableSet<EnrichmentType>,
    ): Pair<EnrichmentRequest, EnrichmentResult?> {
        val provider = registry.identityProvider() ?: return request to null
        // The rethrow is not optional. An earlier note here claimed the bare catch was safe because
        // "cancellation re-asserts at the next suspension point" — that is not a guarantee. Kotlin
        // cancellation is cooperative: a suspend function may return without ever suspending again,
        // and this one only happened to be caught downstream by resolveTypes()'s coroutineScope.
        // It also logged a cancelled call as a provider failure. (#53)
        val result = try {
            provider.resolveIdentity(request)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(TAG, "Identity resolution failed: ${e.message}", e)
            // Tells reclassifyTransientGap "skipped because this run's identity resolution
            // hiccupped" from "skipped because the request genuinely has none of these".
            currentCoroutineContext()[TransientIdentifierMarker]?.markAllConcreteIdentifiers()
            // Must not collapse to null: null identity means "not attempted" and reads as
            // confident. GENRE matches the type the identity provider itself reports under.
            // mapError is the one classifier: consumers key retry policy off ErrorKind, so an
            // AUTH or PARSE failure must not arrive here as UNKNOWN.
            return request to provider.mapError(EnrichmentType.GENRE, e).asRateLimitedIfThrottled()
        }
        if (result !is EnrichmentResult.Success) {
            logger.debug(TAG, "Identity resolution returned ${result::class.simpleName}")
            return request to result
        }

        val resolved = result.resolvedIdentifiers
        if (resolved == null) {
            logger.debug(TAG, "Identity resolution returned no resolvedIdentifiers")
            if (result.data is EnrichmentData.Metadata) {
                for (type in IDENTITY_TYPES) {
                    if (type in uncachedTypes && type !in mergeableTypes && result.data.answers(type)) {
                        results[type] = result.copy(type = type); uncachedTypes.remove(type)
                    }
                }
            }
            return request to result
        }

        logger.debug(
            TAG,
            "Identity resolved: mbid=${resolved.musicBrainzId}, " +
                "wikidataId=${resolved.wikidataId}, wpTitle=${resolved.wikipediaTitle}",
        )

        val mergedIds = EnrichmentIdentifiers(
            musicBrainzId = resolved.musicBrainzId ?: request.identifiers.musicBrainzId,
            musicBrainzReleaseGroupId =
            resolved.musicBrainzReleaseGroupId ?: request.identifiers.musicBrainzReleaseGroupId,
            wikidataId = resolved.wikidataId ?: request.identifiers.wikidataId,
            isrc = resolved.isrc ?: request.identifiers.isrc,
            barcode = resolved.barcode ?: request.identifiers.barcode,
            wikipediaTitle = resolved.wikipediaTitle ?: request.identifiers.wikipediaTitle,
            extra = request.identifiers.extra + resolved.extra,
        )

        if (result.data is EnrichmentData.Metadata) {
            // A type the identity payload does not answer is left in uncachedTypes on purpose, so
            // the provider chain still gets its turn at it rather than inheriting an empty Success.
            for (type in IDENTITY_TYPES) {
                if (type in uncachedTypes && type !in mergeableTypes && result.data.answers(type)) {
                    results[type] = EnrichmentResult.Success(
                        type = type,
                        data = result.data,
                        provider = result.provider,
                        confidence = result.confidence,
                        resolvedIdentifiers = mergedIds,
                    )
                    uncachedTypes.remove(type)
                }
            }
        }

        // Backfill after mergedIds, into blank name fields only: a request built by
        // EnrichmentRequest.forTrackByMbid and its siblings carries an identifier and no name, and
        // every provider but MusicBrainz searches by name. See [withBackfilledNames] for why a name
        // the caller did supply is never overwritten.
        val names = currentCoroutineContext()[ResolvedEntityNames]?.resolved()
        return request.withIdentifiers(mergedIds).withBackfilledNames(names) to result
    }

    private suspend fun resolveTypes(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        identityResult: EnrichmentResult? = null,
    ): Map<EnrichmentType, EnrichmentResult> = coroutineScope {
        // A request identity resolution could not name — an identifier-only one whose identifier
        // MusicBrainz holds nothing under — reaches every provider with a blank title and artist.
        // The name-search providers are asked for nothing at all in that state, so they are not
        // asked: a type no identifier-keyed provider can serve is an honest NotFound, never a live
        // search for the empty string and never an Error.
        val identifierOnly = namesNoEntity(request)
        val compositeTypes = types.filter { it in compositeDependencies }
        val standardTypes = types - compositeTypes.toSet()

        val mergeableRequested = standardTypes.filter { it in mergeableTypes }.toSet()
        val regularTypes = standardTypes - mergeableRequested

        val compositeSubTypes = compositeTypes
            .flatMap { compositeDependencies[it].orEmpty() }
            .toSet() - regularTypes - mergeableRequested

        val allRegularToResolve = regularTypes + compositeSubTypes
        val resolved = allRegularToResolve.map { type ->
            async {
                val chain = registry.chainFor(type)
                val result = chain?.resolve(request, identifierOnly)
                    ?: EnrichmentResult.NotFound(type, "no_provider")
                type to reclassifyTransientGap(chain, request.identifiers, type, gate(result))
            }
        }.awaitAll().toMap().toMutableMap()

        val mergeableResults = mergeableRequested.map { mergeType ->
            async {
                val chain = registry.chainFor(mergeType)
                val allResults = chain?.resolveAll(request, identifierOnly)
                val filtered = allResults?.successes.orEmpty()
                    .mapNotNull { gate(it) as? EnrichmentResult.Success }
                val merger = mergers[mergeType]
                val merged = if (merger == null) {
                    EnrichmentResult.NotFound(mergeType, "no_merger")
                } else {
                    // Also gated on the way out: a merger may return one of its inputs as-is, or
                    // merge to nothing. Confidence is not re-filtered — the inputs already passed,
                    // and a merger's own provider id is not a consumer's override key.
                    demoteUnanswered(guardedStrategy(logger, mergeType, "merger") { merger.merge(filtered) })
                }
                // The merger sees only successes, so "nobody succeeded" reaches it as the same empty
                // list whether the providers had nothing or never answered. The chain's own failure
                // tells those apart — but only where no provider produced a Success at all, so a
                // result this gate dropped for confidence still counts as the chain having been
                // answered. That is the test [ProviderChain.resolve] applies to its own lastFailure.
                val outcome = if (merged is EnrichmentResult.NotFound && allResults?.successes.isNullOrEmpty()) {
                    allResults?.failure ?: merged
                } else {
                    merged
                }
                mergeType to reclassifyTransientGap(chain, request.identifiers, mergeType, outcome)
            }
        }.awaitAll()
        for ((type, result) in mergeableResults) { resolved[type] = result }

        for (compositeType in compositeTypes) {
            val synthesizer = synthesizers[compositeType]
            resolved[compositeType] = if (synthesizer == null) {
                EnrichmentResult.NotFound(compositeType, "no_composite_handler")
            } else {
                // TimelineSynthesizer returns Success even with no events, and CompositeSynthesizer
                // is a public extension point — a consumer's synthesizer has the same freedom.
                demoteUnanswered(
                    guardedStrategy(logger, compositeType, "synthesizer") {
                        synthesizer.synthesize(resolved, identityResult, request)
                    },
                )
            }
        }

        resolved.filterKeys { it in types }
    }

    private fun filterByConfidence(result: EnrichmentResult): EnrichmentResult {
        if (result !is EnrichmentResult.Success) return result
        val override = config.confidenceOverrides[result.provider]
        val effective = override ?: result.confidence
        if (effective < config.minConfidence) {
            return EnrichmentResult.NotFound(result.type, result.provider)
        }
        return if (override != null) result.copy(confidence = override) else result
    }

    /** Both per-result gates, for anything arriving from a provider chain. */
    private fun gate(result: EnrichmentResult): EnrichmentResult = demoteUnanswered(filterByConfidence(result))

    /**
     * Turns a [type]'s `NotFound` into an `Error` when it rode on a provider skipped for an
     * identifier requirement that a transient — somewhere else in this run — left unresolved,
     * rather than genuinely absent. Independent of [ProviderChain.resolve]/
     * [ProviderChain.resolveAll]'s own return values on purpose: a chain can have skipped one
     * provider for an unresolved identifier while a *different*, unrelated provider in the same
     * chain was still eligible, ran, and returned its own genuine `NotFound` (e.g. Last.fm
     * alongside a skipped Wikipedia for `ALBUM_DESCRIPTION`) — the chain's own result would be
     * indistinguishable from "everyone genuinely had nothing" in that case.
     *
     * Benign race: [TransientIdentifierMarker.mark] and this read both run inside the same
     * `enrich()` call's `coroutineScope`, launched as sibling `async` children — a type resolved
     * concurrently with, but *before*, the mark that would have covered it can miss the mark and
     * fall through to today's behaviour (a plain `NotFound`) instead of `Error`. That degrades to
     * the pre-fix outcome, never invents a false `Error` for a genuinely-absent identifier.
     */
    private suspend fun reclassifyTransientGap(
        chain: ProviderChain?,
        identifiers: EnrichmentIdentifiers,
        type: EnrichmentType,
        result: EnrichmentResult,
    ): EnrichmentResult {
        if (result !is EnrichmentResult.NotFound || chain == null) return result
        val marker = currentCoroutineContext()[TransientIdentifierMarker] ?: return result
        val skipped = chain.skippedIdentifierRequirements(identifiers)
        return if (skipped.isNotEmpty() && marker.matches(skipped)) {
            EnrichmentResult.Error(
                type, "engine",
                "A prerequisite identifier lookup failed transiently this run",
                errorKind = ErrorKind.NETWORK,
            )
        } else {
            result
        }
    }

    /**
     * The second per-result gate: a `Success` whose payload does not [answers] the type it claims to
     * answer is a `NotFound` — whether the payload is empty or merely fills some *other* type's
     * field. [filterByConfidence] cannot do this — confidence scores identification, and a perfect
     * identity match on an entity carrying no data scores 1.0. Sited here rather than in each
     * provider because every provider had the same hole. [answers] gates everything that can reach a
     * consumer: chain results, merger and synthesizer output, the identity fan-out, and both cache
     * paths — on the cache read as a *miss*, so the entry is refetched rather than pinned.
     */
    private fun demoteUnanswered(result: EnrichmentResult): EnrichmentResult =
        if (result is EnrichmentResult.Success && !result.data.answers(result.type)) {
            EnrichmentResult.NotFound(result.type, result.provider)
        } else {
            result
        }

    private suspend fun applyStaleCache(
        request: EnrichmentRequest,
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        types: Set<EnrichmentType>,
    ) {
        for (type in types) {
            val result = results[type] ?: continue
            if (result is EnrichmentResult.Error || result is EnrichmentResult.RateLimited) {
                val stale = guardedCacheRead(logger, "getIncludingExpired") {
                    cache.getIncludingExpired(entityKeyFor(request, type), type)
                }
                // A stale entry that answers nothing is worse than the Error it would replace:
                // the Error at least tells the consumer to retry.
                if (stale != null && stale.data.answers(type)) {
                    results[type] = stale.copy(isStale = true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "EnrichmentEngine"

        private val IDENTITY_TYPES = setOf(
            EnrichmentType.GENRE, EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE,
            EnrichmentType.RELEASE_TYPE, EnrichmentType.COUNTRY,
        )

        /** @see entityKeyFor */
        fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String =
            com.landofoz.musicmeta.engine.entityKeyFor(request, type)

        /** @see entityKeyForName */
        fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
            com.landofoz.musicmeta.engine.entityKeyForName(request, type)
    }
}
