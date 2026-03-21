package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Provides artist photos from Wikidata's P18 (image) property.
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

        val imageUrl = api.getArtistImageUrl(wikidataId, imageSize)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Artwork(url = imageUrl),
            provider = id,
            confidence = CONFIDENCE,
        )
    }

    companion object {
        const val DEFAULT_IMAGE_SIZE = 1200
        private const val PRIORITY = 100
        /** MBID-based Wikidata ID lookup. Authoritative source, but image quality varies. */
        private const val CONFIDENCE = 0.9f
    }
}
