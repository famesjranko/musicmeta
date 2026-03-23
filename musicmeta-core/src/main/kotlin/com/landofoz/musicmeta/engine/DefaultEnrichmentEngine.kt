package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.HttpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    ): Map<EnrichmentType, EnrichmentResult> {
        val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
        val uncachedTypes = mutableSetOf<EnrichmentType>()

        for (type in types) {
            val cached = cache.get(entityKeyFor(request, type), type)
            if (cached != null) results[type] = cached else uncachedTypes.add(type)
        }
        if (uncachedTypes.isEmpty()) return results

        try {
            withTimeout(config.enrichTimeoutMs) {
                var identityResult: EnrichmentResult? = null
                val enrichedRequest = if (config.enableIdentityResolution && needsIdentityResolution(request, uncachedTypes)) {
                    resolveIdentity(request, results, uncachedTypes).also { identityResult = it.second }.first
                } else request

                results.putAll(resolveTypes(enrichedRequest, uncachedTypes, identityResult))
                applyCatalogFiltering(results)
                stampIdentityScore(results, identityResult)
                propagateIdentitySuggestions(results, identityResult)
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn(TAG, "Enrich timed out after ${config.enrichTimeoutMs}ms")
            for (type in types) {
                if (type !in results) {
                    results[type] = EnrichmentResult.Error(type, "engine", "Enrichment timed out", errorKind = ErrorKind.TIMEOUT)
                }
            }
        }

        for ((type, result) in results) {
            if (result is EnrichmentResult.Success) {
                cache.put(entityKeyFor(request, type), type, result, config.ttlOverrides[type] ?: type.defaultTtlMs)
            }
        }
        return results
    }

    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
        // Identity provider (MusicBrainz) is the primary search source
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

        // Supplement with other providers if primary didn't fill the limit
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

        // Deduplicate by title+artist (supplemental may overlap with primary)
        val primaryTitles = primary.map { "${it.title}:${it.artist}".lowercase() }.toSet()
        val unique = supplemental.filter {
            "${it.title}:${it.artist}".lowercase() !in primaryTitles
        }
        return (primary + unique).take(limit)
    }

    override fun getProviders(): List<ProviderInfo> = registry.providerInfos()

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

        // Extract resolved identifiers from the result
        val resolved = result.resolvedIdentifiers
        if (resolved == null) {
            logger.debug(TAG, "Identity resolution returned no resolvedIdentifiers")
            // Still store the result for identity types if data is Metadata
            // Skip mergeable types (e.g. GENRE) — they need multi-provider resolution
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

        // Merge resolved identifiers with existing ones (resolved takes precedence)
        val mergedIds = EnrichmentIdentifiers(
            musicBrainzId = resolved.musicBrainzId ?: request.identifiers.musicBrainzId,
            musicBrainzReleaseGroupId = resolved.musicBrainzReleaseGroupId ?: request.identifiers.musicBrainzReleaseGroupId,
            wikidataId = resolved.wikidataId ?: request.identifiers.wikidataId,
            wikipediaTitle = resolved.wikipediaTitle ?: request.identifiers.wikipediaTitle,
            extra = request.identifiers.extra + resolved.extra,
        )

        // Store Metadata result for all identity types with resolved identifiers attached
        // Skip mergeable types (e.g. GENRE) — they need multi-provider resolution
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

        // Split standard types into mergeable (e.g. GENRE) vs regular (short-circuit)
        val mergeableRequested = standardTypes.filter { it in mergeableTypes }.toSet()
        val regularTypes = standardTypes - mergeableRequested

        // Composite types need sub-type resolution first
        val compositeSubTypes = compositeTypes
            .flatMap { compositeDependencies[it] ?: emptySet() }
            .toSet() - regularTypes - mergeableRequested

        // Resolve regular types + composite sub-types concurrently (short-circuit behavior)
        val allRegularToResolve = regularTypes + compositeSubTypes
        val resolved = allRegularToResolve.map { type ->
            async {
                val chain = registry.chainFor(type)
                val result = chain?.resolve(request) ?: EnrichmentResult.NotFound(type, "no_provider")
                type to filterByConfidence(result)
            }
        }.awaitAll().toMap().toMutableMap()

        // Resolve mergeable types concurrently — collect ALL provider results then merge
        val mergeableResults = mergeableRequested.map { mergeType ->
            async {
                val chain = registry.chainFor(mergeType)
                val allResults = chain?.resolveAll(request) ?: emptyList()
                val filtered = allResults.mapNotNull { filterByConfidence(it) as? EnrichmentResult.Success }
                mergeType to (mergers[mergeType]?.merge(filtered) ?: EnrichmentResult.NotFound(mergeType, "no_merger"))
            }
        }.awaitAll()
        for ((type, result) in mergeableResults) { resolved[type] = result }

        // Synthesize composite types from resolved sub-types
        for (compositeType in compositeTypes) {
            resolved[compositeType] = synthesizers[compositeType]?.synthesize(resolved, identityResult, request)
                ?: EnrichmentResult.NotFound(compositeType, "no_composite_handler")
        }

        // Only return types the caller requested — exclude internal sub-types
        resolved.filterKeys { it in types }
    }

    /**
     * Apply confidence overrides from config, then filter below minConfidence.
     * If a provider has a confidence override in config, replace the hardcoded value.
     */
    private fun filterByConfidence(result: EnrichmentResult): EnrichmentResult {
        if (result !is EnrichmentResult.Success) return result
        val override = config.confidenceOverrides[result.provider]
        val effective = override ?: result.confidence
        if (effective < config.minConfidence) {
            return EnrichmentResult.NotFound(result.type, result.provider)
        }
        return if (override != null) result.copy(confidence = override) else result
    }

    /** Stamps identity match score on all freshly resolved Success results. */
    private fun stampIdentityScore(
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
    ) {
        val score = (identityResult as? EnrichmentResult.Success)
            ?.let { (it.confidence * 100).toInt() } ?: return
        for ((type, result) in results) {
            if (result is EnrichmentResult.Success && result.identityMatchScore == null) {
                results[type] = result.copy(identityMatchScore = score)
            }
        }
    }

    /** Propagates identity resolution suggestions to all NotFound results. */
    private fun propagateIdentitySuggestions(
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
    ) {
        val suggestions = (identityResult as? EnrichmentResult.NotFound)?.suggestions ?: return
        for ((type, result) in results) {
            if (result is EnrichmentResult.NotFound && result.suggestions == null) {
                results[type] = result.copy(suggestions = suggestions)
            }
        }
    }

    /** Applies catalog availability filtering to recommendation results in-place. No-op when null or UNFILTERED. */
    private suspend fun applyCatalogFiltering(results: MutableMap<EnrichmentType, EnrichmentResult>) {
        val provider = config.catalogProvider ?: return
        val mode = config.catalogFilterMode
        if (mode == CatalogFilterMode.UNFILTERED) return

        for (type in RECOMMENDATION_TYPES) {
            val result = results[type] as? EnrichmentResult.Success ?: continue
            val queries = toQueries(result.data) ?: continue
            if (queries.isEmpty()) continue

            val matches = provider.checkAvailability(queries)
            results[type] = applyMode(result, matches, mode)
        }
    }

    private fun needsIdentityResolution(request: EnrichmentRequest, types: Set<EnrichmentType>): Boolean {
        val ids = request.identifiers
        // Always need identity if we have no MBID at all (identity provider resolves it)
        if (ids.musicBrainzId == null && ids.musicBrainzReleaseGroupId == null) return true
        // Check if any requested type has providers that need identifiers we're missing
        for (type in types) {
            val chain = registry.chainFor(type) ?: continue
            for (provider in chain.providers()) {
                val cap = provider.capabilities.firstOrNull { it.type == type } ?: continue
                val missing = when (cap.identifierRequirement) {
                    IdentifierRequirement.NONE -> false
                    IdentifierRequirement.MUSICBRAINZ_ID -> ids.musicBrainzId == null
                    IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID -> ids.musicBrainzReleaseGroupId == null
                    IdentifierRequirement.WIKIDATA_ID -> ids.wikidataId == null
                    IdentifierRequirement.WIKIPEDIA_TITLE -> ids.wikipediaTitle == null && ids.wikidataId == null
                    IdentifierRequirement.ANY_IDENTIFIER -> ids.musicBrainzId == null &&
                        ids.musicBrainzReleaseGroupId == null &&
                        ids.wikidataId == null && ids.wikipediaTitle == null
                }
                if (missing) return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "EnrichmentEngine"

        private val IDENTITY_TYPES = setOf(
            EnrichmentType.GENRE, EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE,
            EnrichmentType.RELEASE_TYPE, EnrichmentType.COUNTRY,
        )

        fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String {
            val prefix = when (request) {
                is EnrichmentRequest.ForAlbum -> "album"
                is EnrichmentRequest.ForArtist -> "artist"
                is EnrichmentRequest.ForTrack -> "track"
            }
            val id = request.identifiers.musicBrainzId ?: when (request) {
                is EnrichmentRequest.ForAlbum -> "${request.artist}:${request.title}"
                is EnrichmentRequest.ForArtist -> request.name
                is EnrichmentRequest.ForTrack -> "${request.artist}:${request.title}"
            }
            return "$prefix:$id:$type"
        }
    }
}
