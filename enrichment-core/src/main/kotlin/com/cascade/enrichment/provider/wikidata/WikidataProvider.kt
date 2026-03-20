package com.cascade.enrichment.provider.wikidata

import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentProvider
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.ProviderCapability
import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter

/**
 * Provides artist photos from Wikidata's P18 (image) property.
 * Requires a wikidataId in the request identifiers.
 */
class WikidataProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    private val api = WikidataApi(httpClient, rateLimiter)

    override val id: String = "wikidata"
    override val displayName: String = "Wikidata"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.ARTIST_PHOTO,
            priority = PRIORITY,
            requiresIdentifier = true,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val wikidataId = request.identifiers.wikidataId
        if (wikidataId.isNullOrBlank()) {
            return EnrichmentResult.NotFound(type, id)
        }

        val imageUrl = api.getArtistImageUrl(wikidataId)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Artwork(url = imageUrl),
            provider = id,
            confidence = CONFIDENCE,
        )
    }

    private companion object {
        const val PRIORITY = 100
        /** MBID-based Wikidata ID lookup. Authoritative source, but image quality varies. */
        const val CONFIDENCE = 0.9f
    }
}
