package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
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
) : EnrichmentEngine {

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
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn(TAG, "Enrich timed out after ${config.enrichTimeoutMs}ms, returning partial results")
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
        val identityType = IDENTITY_TYPES.firstOrNull { it in uncachedTypes } ?: IDENTITY_TYPES.first()

        val result = try {
            provider.resolveIdentity(request)
        } catch (e: Exception) {
            logger.warn(TAG, "Identity resolution failed: ${e.message}", e)
            return request to null
        }
        if (result !is EnrichmentResult.Success) {
            logger.debug(TAG, "Identity resolution returned ${result::class.simpleName}")
            return request to null
        }

        // Extract resolved identifiers from the result
        val resolved = result.resolvedIdentifiers
        if (resolved == null) {
            logger.debug(TAG, "Identity resolution returned no resolvedIdentifiers")
            // Still store the result for identity types if data is Metadata
            // Skip mergeable types (e.g. GENRE) — they need multi-provider resolution
            if (result.data is EnrichmentData.Metadata) {
                for (type in IDENTITY_TYPES) {
                    if (type in uncachedTypes && type !in MERGEABLE_TYPES) {
                        results[type] = result; uncachedTypes.remove(type)
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
            val metadataResult = EnrichmentResult.Success(
                type = identityType,
                data = result.data,
                provider = result.provider,
                confidence = result.confidence,
                resolvedIdentifiers = mergedIds,
            )
            for (type in IDENTITY_TYPES) {
                if (type in uncachedTypes && type !in MERGEABLE_TYPES) {
                    results[type] = metadataResult; uncachedTypes.remove(type)
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
        val compositeTypes = types.filter { it in COMPOSITE_DEPENDENCIES }
        val standardTypes = types - compositeTypes.toSet()

        // Split standard types into mergeable (e.g. GENRE) vs regular (short-circuit)
        val mergeableTypes = standardTypes.filter { it in MERGEABLE_TYPES }.toSet()
        val regularTypes = standardTypes - mergeableTypes

        // Composite types need sub-type resolution first
        val compositeSubTypes = compositeTypes
            .flatMap { COMPOSITE_DEPENDENCIES[it] ?: emptySet() }
            .toSet() - regularTypes - mergeableTypes

        // Resolve regular types + composite sub-types concurrently (short-circuit behavior)
        val allRegularToResolve = regularTypes + compositeSubTypes
        val resolved = allRegularToResolve.map { type ->
            async {
                val chain = registry.chainFor(type)
                val result = chain?.resolve(request) ?: EnrichmentResult.NotFound(type, "no_provider")
                type to filterByConfidence(result)
            }
        }.awaitAll().toMap().toMutableMap()

        // Resolve mergeable types — collect ALL provider results then merge
        for (mergeType in mergeableTypes) {
            val deferred = async {
                val chain = registry.chainFor(mergeType)
                val allResults = chain?.resolveAll(request) ?: emptyList()
                // Apply confidence filter per-result before merge
                val filtered = allResults.mapNotNull { filterByConfidence(it) as? EnrichmentResult.Success }
                mergeType to mergeGenreResults(mergeType, filtered)
            }
            resolved[mergeType] = deferred.await().second
        }

        // Synthesize composite types from resolved sub-types
        for (compositeType in compositeTypes) {
            resolved[compositeType] = synthesizeComposite(compositeType, resolved, identityResult, request)
        }

        // Only return types the caller requested — exclude internal sub-types
        resolved.filterKeys { it in types }
    }

    private fun mergeGenreResults(
        type: EnrichmentType,
        results: List<EnrichmentResult.Success>,
    ): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        // Collect all genreTags from Metadata results
        val allTags = results.flatMap { result ->
            (result.data as? EnrichmentData.Metadata)?.genreTags.orEmpty()
        }
        if (allTags.isEmpty()) {
            // Fallback: return first success as-is (providers may not have genreTags yet)
            return results.first()
        }

        val merged = GenreMerger.merge(allTags)
        val topGenres = merged.take(10).map { it.name }
        val maxConfidence = results.maxOf { it.confidence }

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Metadata(
                genres = topGenres.takeIf { it.isNotEmpty() },
                genreTags = merged.takeIf { it.isNotEmpty() },
            ),
            provider = "genre_merger",
            confidence = maxConfidence,
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
        )
    }

    private fun synthesizeComposite(
        type: EnrichmentType,
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult {
        return when (type) {
            EnrichmentType.ARTIST_TIMELINE -> synthesizeTimeline(resolved, identityResult, request)
            else -> EnrichmentResult.NotFound(type, "no_composite_handler")
        }
    }

    private fun synthesizeTimeline(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForArtist) {
            return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TIMELINE, "artist_only")
        }
        val discography = resolved[EnrichmentType.ARTIST_DISCOGRAPHY]
        val bandMembers = resolved[EnrichmentType.BAND_MEMBERS]

        val timeline = TimelineSynthesizer.synthesize(identityResult, discography, bandMembers)
        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_TIMELINE,
            data = timeline,
            provider = "timeline_synthesizer",
            confidence = ConfidenceCalculator.authoritative(),
        )
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

        /** EnrichmentTypes that collect ALL provider results and merge them instead of short-circuiting. */
        private val MERGEABLE_TYPES = setOf(EnrichmentType.GENRE)

        /** Maps composite EnrichmentTypes to their required sub-type dependencies. */
        private val COMPOSITE_DEPENDENCIES = mapOf(
            EnrichmentType.ARTIST_TIMELINE to setOf(
                EnrichmentType.ARTIST_DISCOGRAPHY,
                EnrichmentType.BAND_MEMBERS,
            ),
        )

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
