package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Discogs enrichment provider. Searches for album releases to supply
 * cover art and label metadata. Requires a Discogs personal access token.
 */
class DiscogsProvider(
    private val tokenProvider: () -> String,
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    constructor(personalToken: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ personalToken }, httpClient, rateLimiter)

    private val api = DiscogsApi(tokenProvider, httpClient, rateLimiter)

    override val id = "discogs"
    override val displayName = "Discogs"
    override val requiresApiKey = true
    override val isAvailable: Boolean get() = tokenProvider().isNotBlank()

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 20),
        ProviderCapability(EnrichmentType.LABEL, priority = 50),
        ProviderCapability(EnrichmentType.RELEASE_TYPE, priority = 50),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (!isAvailable) return EnrichmentResult.NotFound(type, id)
        val albumRequest = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(type, id)

        return try {
            val release = api.searchRelease(albumRequest.title, albumRequest.artist)
                ?: return EnrichmentResult.NotFound(type, id)
            enrichFromRelease(release, type)
        } catch (e: Exception) {
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }
    }

    private fun enrichFromRelease(
        release: DiscogsRelease,
        type: EnrichmentType,
    ): EnrichmentResult {
        return when (type) {
            EnrichmentType.ALBUM_ART -> {
                val url = release.coverImage
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Artwork(url = url), type)
            }
            EnrichmentType.LABEL -> {
                val label = release.label
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Metadata(label = label), type)
            }
            EnrichmentType.RELEASE_TYPE -> {
                val releaseType = release.releaseType
                    ?: return EnrichmentResult.NotFound(type, id)
                success(EnrichmentData.Metadata(releaseType = releaseType), type)
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
        /** Fuzzy search, physical-release focus. Digital albums may not match well. */
        const val CONFIDENCE = 0.6f
    }
}
