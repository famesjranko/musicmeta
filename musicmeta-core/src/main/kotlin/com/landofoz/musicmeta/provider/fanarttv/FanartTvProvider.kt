package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.EnrichmentData
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
 * Fanart.tv enrichment provider. Supplies high-quality artist images
 * (photos, backgrounds, logos). Requires a MusicBrainz ID in the request
 * identifiers and a Fanart.tv project API key.
 */
class FanartTvProvider(
    private val projectKeyProvider: () -> String,
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    constructor(projectKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ projectKey }, httpClient, rateLimiter)

    private val api = FanartTvApi(projectKeyProvider, httpClient, rateLimiter)

    override val id = "fanarttv"
    override val displayName = "Fanart.tv"
    override val requiresApiKey = true
    override val isAvailable: Boolean get() = projectKeyProvider().isNotBlank()

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ARTIST_PHOTO, priority = 80, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.ARTIST_BACKGROUND, priority = 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.ARTIST_LOGO, priority = 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 30, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.CD_ART, priority = 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.ARTIST_BANNER, priority = 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (!isAvailable) return EnrichmentResult.NotFound(type, id)
        val mbid = request.identifiers.musicBrainzId
            ?: return EnrichmentResult.NotFound(type, id)

        // Album art and CD art need artist context, artist types need ForArtist
        if (type != EnrichmentType.ALBUM_ART && type != EnrichmentType.CD_ART &&
            request !is EnrichmentRequest.ForArtist
        ) {
            return EnrichmentResult.NotFound(type, id)
        }

        return try {
            val images = api.getArtistImages(mbid)
                ?: return EnrichmentResult.NotFound(type, id)
            enrichFromImages(images, type)
        } catch (e: Exception) {
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }
    }

    private fun enrichFromImages(
        images: FanartTvArtistImages,
        type: EnrichmentType,
    ): EnrichmentResult {
        val imageList = when (type) {
            EnrichmentType.ARTIST_PHOTO -> images.thumbnails
            EnrichmentType.ARTIST_BACKGROUND -> images.backgrounds
            EnrichmentType.ARTIST_LOGO -> images.logos
            EnrichmentType.ALBUM_ART -> images.albumCovers
            EnrichmentType.CD_ART -> images.cdArt
            EnrichmentType.ARTIST_BANNER -> images.banners
            else -> null
        } ?: return EnrichmentResult.NotFound(type, id)
        val image = imageList.firstOrNull() ?: return EnrichmentResult.NotFound(type, id)
        return success(FanartTvMapper.toArtwork(image, imageList), type)
    }

    private fun success(data: EnrichmentData, type: EnrichmentType) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = ConfidenceCalculator.authoritative(),
    )
}
