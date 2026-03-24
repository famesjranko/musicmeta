package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.http.HttpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class DefaultEnrichmentEngine(
    private val registry: ProviderRegistry,
    override val cache: EnrichmentCache,
    private val httpClient: HttpClient,
    private val config: EnrichmentConfig,
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
            for (type in types) invalidateKeys(request, type)
        }

        val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
        val uncachedTypes = mutableSetOf<EnrichmentType>()

        for (type in types) {
            val cached = cache.get(entityKeyFor(request, type), type)
            if (cached != null) results[type] = cached else uncachedTypes.add(type)
        }
        if (uncachedTypes.isEmpty()) {
            return EnrichmentResults(results, types, identity = null)
        }

        var identityResolution: IdentityResolution? = null

        try {
            withTimeout(config.enrichTimeoutMs) {
                var identityResult: EnrichmentResult? = null
                val enrichedRequest = if (config.enableIdentityResolution && needsIdentityResolution(request, uncachedTypes, registry)) {
                    resolveIdentity(request, results, uncachedTypes).also { identityResult = it.second }.first
                } else request

                identityResolution = buildIdentityResolution(identityResult, enrichedRequest)

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
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn(TAG, "Enrich timed out after ${config.enrichTimeoutMs}ms")
            for (type in types) {
                if (type !in results) {
                    results[type] = EnrichmentResult.Error(type, "engine", "Enrichment timed out", errorKind = ErrorKind.TIMEOUT)
                }
            }
        }

        if (config.cacheMode == CacheMode.STALE_IF_ERROR) {
            applyStaleCache(request, results, uncachedTypes)
        }

        val resolvedMbid = identityResolution?.identifiers?.musicBrainzId
        for ((type, result) in results) {
            if (result is EnrichmentResult.Success && !result.isStale) {
                val ttl = config.ttlOverrides[type] ?: type.defaultTtlMs
                val primaryKey = entityKeyFor(request, type)
                cache.put(primaryKey, type, result, ttl)

                // Alias: when identity resolution added an MBID, also cache under the
                // name-based key so future name-only lookups find MBID-resolved data.
                if (resolvedMbid != null && request.identifiers.musicBrainzId == null) {
                    val nameKey = entityKeyForName(request, type)
                    cache.put(nameKey, type, result, ttl)
                }
            }
        }
        return EnrichmentResults(results, types, identityResolution)
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
        for (t in types) invalidateKeys(request, t)
    }

    override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean =
        cache.isManuallySelected(entityKeyFor(request, type), type)

    override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) {
        cache.markManuallySelected(entityKeyFor(request, type), type)
    }

    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
        val identity = registry.identityProvider()
        val primary = if (identity != null) {
            try {
                identity.searchCandidates(request, limit)
            } catch (e: Exception) {
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

    /** Invalidates both the primary key and the name-alias key for a request/type. */
    private suspend fun invalidateKeys(request: EnrichmentRequest, type: EnrichmentType) {
        cache.invalidate(entityKeyFor(request, type), type)
        if (request.identifiers.musicBrainzId != null) {
            cache.invalidate(entityKeyForName(request, type), type)
        }
    }

    /** Returns the enriched request and the raw identity result (for composite type synthesis). */
    private suspend fun resolveIdentity(
        request: EnrichmentRequest,
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        uncachedTypes: MutableSet<EnrichmentType>,
    ): Pair<EnrichmentRequest, EnrichmentResult?> {
        val provider = registry.identityProvider() ?: return request to null
        val result = try {
            provider.resolveIdentity(request)
        } catch (e: Exception) {
            logger.warn(TAG, "Identity resolution failed: ${e.message}", e)
            return request to null
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
                    if (type in uncachedTypes && type !in mergeableTypes) {
                        results[type] = result.copy(type = type); uncachedTypes.remove(type)
                    }
                }
            }
            return request to result
        }

        logger.debug(TAG, "Identity resolved: mbid=${resolved.musicBrainzId}, wikidataId=${resolved.wikidataId}, wpTitle=${resolved.wikipediaTitle}")

        val mergedIds = EnrichmentIdentifiers(
            musicBrainzId = resolved.musicBrainzId ?: request.identifiers.musicBrainzId,
            musicBrainzReleaseGroupId = resolved.musicBrainzReleaseGroupId ?: request.identifiers.musicBrainzReleaseGroupId,
            wikidataId = resolved.wikidataId ?: request.identifiers.wikidataId,
            isrc = resolved.isrc ?: request.identifiers.isrc,
            barcode = resolved.barcode ?: request.identifiers.barcode,
            wikipediaTitle = resolved.wikipediaTitle ?: request.identifiers.wikipediaTitle,
            extra = request.identifiers.extra + resolved.extra,
        )

        if (result.data is EnrichmentData.Metadata) {
            for (type in IDENTITY_TYPES) {
                if (type in uncachedTypes && type !in mergeableTypes) {
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

        return request.withIdentifiers(mergedIds) to result
    }

    private suspend fun resolveTypes(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        identityResult: EnrichmentResult? = null,
    ): Map<EnrichmentType, EnrichmentResult> = coroutineScope {
        val compositeTypes = types.filter { it in compositeDependencies }
        val standardTypes = types - compositeTypes.toSet()

        val mergeableRequested = standardTypes.filter { it in mergeableTypes }.toSet()
        val regularTypes = standardTypes - mergeableRequested

        val compositeSubTypes = compositeTypes
            .flatMap { compositeDependencies[it] ?: emptySet() }
            .toSet() - regularTypes - mergeableRequested

        val allRegularToResolve = regularTypes + compositeSubTypes
        val resolved = allRegularToResolve.map { type ->
            async {
                val chain = registry.chainFor(type)
                val result = chain?.resolve(request) ?: EnrichmentResult.NotFound(type, "no_provider")
                type to filterByConfidence(result)
            }
        }.awaitAll().toMap().toMutableMap()

        val mergeableResults = mergeableRequested.map { mergeType ->
            async {
                val chain = registry.chainFor(mergeType)
                val allResults = chain?.resolveAll(request) ?: emptyList()
                val filtered = allResults.mapNotNull { filterByConfidence(it) as? EnrichmentResult.Success }
                mergeType to (mergers[mergeType]?.merge(filtered) ?: EnrichmentResult.NotFound(mergeType, "no_merger"))
            }
        }.awaitAll()
        for ((type, result) in mergeableResults) { resolved[type] = result }

        for (compositeType in compositeTypes) {
            resolved[compositeType] = synthesizers[compositeType]?.synthesize(resolved, identityResult, request)
                ?: EnrichmentResult.NotFound(compositeType, "no_composite_handler")
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

    private suspend fun applyStaleCache(
        request: EnrichmentRequest,
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        types: Set<EnrichmentType>,
    ) {
        for (type in types) {
            val result = results[type] ?: continue
            if (result is EnrichmentResult.Error || result is EnrichmentResult.RateLimited) {
                val stale = cache.getIncludingExpired(entityKeyFor(request, type), type)
                if (stale != null) {
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
