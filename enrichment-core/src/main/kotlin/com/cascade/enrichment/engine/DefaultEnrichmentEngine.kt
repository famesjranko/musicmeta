package com.cascade.enrichment.engine

import com.cascade.enrichment.EnrichmentCache
import com.cascade.enrichment.EnrichmentConfig
import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentEngine
import com.cascade.enrichment.EnrichmentIdentifiers
import com.cascade.enrichment.EnrichmentLogger
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.ProviderInfo
import com.cascade.enrichment.SearchCandidate
import com.cascade.enrichment.http.HttpClient
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
                val enrichedRequest = if (config.enableIdentityResolution && needsIdentityResolution(request, uncachedTypes)) {
                    resolveIdentity(request, results, uncachedTypes)
                } else request

                results.putAll(resolveTypes(enrichedRequest, uncachedTypes))
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn(TAG, "Enrich timed out after ${config.enrichTimeoutMs}ms, returning partial results")
        }

        for ((type, result) in results) {
            if (result is EnrichmentResult.Success) {
                cache.put(entityKeyFor(request, type), type, result, ttlFor(type))
            }
        }
        return results
    }

    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
        // Identity provider (MusicBrainz) is the primary search source
        val identity = registry.identityProvider()
        val primary = if (identity != null) {
            try { identity.searchCandidates(request, limit) } catch (_: Exception) { emptyList() }
        } else emptyList()

        if (primary.size >= limit) return primary.take(limit)

        // Supplement with other providers if primary didn't fill the limit
        val remaining = limit - primary.size
        val supplemental = registry.searchProviders().flatMap { provider ->
            if (!provider.isAvailable) return@flatMap emptyList()
            try { provider.searchCandidates(request, remaining) } catch (_: Exception) { emptyList() }
        }

        // Deduplicate by title+artist (supplemental may overlap with primary)
        val primaryTitles = primary.map { "${it.title}:${it.artist}".lowercase() }.toSet()
        val unique = supplemental.filter {
            "${it.title}:${it.artist}".lowercase() !in primaryTitles
        }
        return (primary + unique).take(limit)
    }

    override fun getProviders(): List<ProviderInfo> = registry.providerInfos()

    private suspend fun resolveIdentity(
        request: EnrichmentRequest,
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        uncachedTypes: MutableSet<EnrichmentType>,
    ): EnrichmentRequest {
        val provider = registry.identityProvider() ?: return request
        val identityType = IDENTITY_TYPES.firstOrNull { it in uncachedTypes } ?: IDENTITY_TYPES.first()

        val result = try { provider.enrich(request, identityType) } catch (_: Exception) { return request }
        if (result !is EnrichmentResult.Success) {
            logger.debug(TAG, "Identity resolution returned ${result::class.simpleName}")
            return request
        }

        val data = result.data
        if (data is EnrichmentData.IdentifierResolution) {
            logger.debug(TAG, "Identity resolved: wikidataId=${data.wikidataId}, wpTitle=${data.wikipediaTitle}")

            val resolvedIds = EnrichmentIdentifiers(
                musicBrainzId = data.musicBrainzId ?: request.identifiers.musicBrainzId,
                musicBrainzReleaseGroupId = data.musicBrainzReleaseGroupId ?: request.identifiers.musicBrainzReleaseGroupId,
                wikidataId = data.wikidataId ?: request.identifiers.wikidataId,
                wikipediaTitle = data.wikipediaTitle ?: request.identifiers.wikipediaTitle,
            )

            // Store Metadata (not IdentifierResolution) for metadata types, with identifiers attached
            val metadata = data.metadata
            if (metadata != null) {
                val metadataResult = EnrichmentResult.Success(
                    type = identityType,
                    data = metadata,
                    provider = result.provider,
                    confidence = result.confidence,
                    resolvedIdentifiers = resolvedIds,
                )
                for (type in IDENTITY_TYPES) {
                    if (type in uncachedTypes) { results[type] = metadataResult; uncachedTypes.remove(type) }
                }
            }

            return request.withIdentifiers(resolvedIds)
        }
        if (data is EnrichmentData.Metadata) { results[identityType] = result; uncachedTypes.remove(identityType) }
        return request
    }

    private suspend fun resolveTypes(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
    ): Map<EnrichmentType, EnrichmentResult> = coroutineScope {
        types.map { type ->
            async {
                val chain = registry.chainFor(type)
                val result = chain?.resolve(request) ?: EnrichmentResult.NotFound(type, "no_provider")
                type to filterByConfidence(result)
            }
        }.awaitAll().toMap()
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
        // No MBID at all — definitely need identity resolution
        if (ids.musicBrainzId == null && ids.musicBrainzReleaseGroupId == null) return true
        // Have MBID but missing identifiers that requested types need
        if (ids.wikidataId == null && types.contains(EnrichmentType.ARTIST_PHOTO)) return true
        if (ids.wikipediaTitle == null && types.contains(EnrichmentType.ARTIST_BIO)) return true
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

        fun ttlFor(type: EnrichmentType): Long = when (type) {
            EnrichmentType.ALBUM_ART, EnrichmentType.ARTIST_LOGO, EnrichmentType.CD_ART,
            EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN -> 90L * 24 * 60 * 60 * 1000
            EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND,
            EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.ARTIST_BIO -> 30L * 24 * 60 * 60 * 1000
            EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE, EnrichmentType.RELEASE_TYPE,
            EnrichmentType.COUNTRY -> 365L * 24 * 60 * 60 * 1000
            EnrichmentType.TRACK_POPULARITY, EnrichmentType.ARTIST_POPULARITY -> 7L * 24 * 60 * 60 * 1000
        }
    }
}
