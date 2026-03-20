package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
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
        ProviderCapability(EnrichmentType.ARTIST_PHOTO, priority = 80, requiresIdentifier = true),
        ProviderCapability(EnrichmentType.ARTIST_BACKGROUND, priority = 100, requiresIdentifier = true),
        ProviderCapability(EnrichmentType.ARTIST_LOGO, priority = 100, requiresIdentifier = true),
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 30, requiresIdentifier = true),
        ProviderCapability(EnrichmentType.CD_ART, priority = 100, requiresIdentifier = true),
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
        return when (type) {
            EnrichmentType.ARTIST_PHOTO -> {
                val url = images.thumbnails.firstOrNull()
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Artwork(url = url), type)
            }
            EnrichmentType.ARTIST_BACKGROUND -> {
                val url = images.backgrounds.firstOrNull()
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Artwork(url = url), type)
            }
            EnrichmentType.ARTIST_LOGO -> {
                val url = images.logos.firstOrNull()
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Artwork(url = url), type)
            }
            EnrichmentType.ALBUM_ART -> {
                val url = images.albumCovers.firstOrNull()
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Artwork(url = url), type)
            }
            EnrichmentType.CD_ART -> {
                val url = images.cdArt.firstOrNull()
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Artwork(url = url), type)
            }
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private fun success(data: EnrichmentData, type: EnrichmentType) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = CONFIDENCE,
    )

    private companion object {
        /** MBID-based lookup. Community-curated images, authoritative when available. */
        const val CONFIDENCE = 0.9f
    }
}
