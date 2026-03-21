package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Provides artist photos and metadata from Wikidata properties.
 * P18 = image, P569 = birth date, P570 = death date,
 * P495 = country of origin, P106 = occupation.
 * Requires a wikidataId in the request identifiers.
 */
class WikidataProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
    private val imageSize: Int = DEFAULT_IMAGE_SIZE,
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
            identifierRequirement = IdentifierRequirement.WIKIDATA_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.COUNTRY,
            priority = 50,
            identifierRequirement = IdentifierRequirement.WIKIDATA_ID,
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

        val props = api.getEntityProperties(wikidataId, imageSize)
            ?: return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.ARTIST_PHOTO -> {
                val imageUrl = props.imageUrl
                    ?: return EnrichmentResult.NotFound(type, id)
                EnrichmentResult.Success(type, WikidataMapper.toArtwork(imageUrl), id, ConfidenceCalculator.authoritative())
            }
            EnrichmentType.COUNTRY -> {
                val metadata = WikidataMapper.toMetadata(props)
                if (metadata.country == null && metadata.beginDate == null &&
                    metadata.endDate == null && metadata.artistType == null
                ) {
                    return EnrichmentResult.NotFound(type, id)
                }
                EnrichmentResult.Success(type, metadata, id, ConfidenceCalculator.authoritative())
            }
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    companion object {
        const val DEFAULT_IMAGE_SIZE = 1200
        private const val PRIORITY = 100
    }
}
