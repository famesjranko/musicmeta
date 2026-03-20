package com.cascade.enrichment.provider.coverartarchive

import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentProvider
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.ProviderCapability
import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter

/**
 * Enrichment provider for album cover art from the Cover Art Archive.
 * Requires a MusicBrainz release or release-group ID.
 */
class CoverArtArchiveProvider(
    httpClient: HttpClient,
    @Suppress("UNUSED_PARAMETER") rateLimiter: RateLimiter,
) : EnrichmentProvider {

    private val api = CoverArtArchiveApi(httpClient)

    override val id: String = "coverartarchive"
    override val displayName: String = "Cover Art Archive"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.ALBUM_ART,
            priority = 100,
            requiresIdentifier = true,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val releaseId = request.identifiers.musicBrainzId
            val groupId = request.identifiers.musicBrainzReleaseGroupId

            if (releaseId == null && groupId == null) {
                return EnrichmentResult.NotFound(type, id)
            }

            findArtwork(releaseId, groupId, type)
        } catch (e: Exception) {
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }
    }

    private suspend fun findArtwork(
        releaseId: String?,
        groupId: String?,
        type: EnrichmentType,
    ): EnrichmentResult {
        // Try release-specific art first
        if (releaseId != null) {
            val url = api.getArtworkUrl(releaseId, SIZE_FULL)
            if (url != null) {
                val thumbUrl = api.getArtworkUrl(releaseId, SIZE_THUMB)
                return EnrichmentResult.Success(
                    type = type,
                    data = EnrichmentData.Artwork(
                        url = url,
                        thumbnailUrl = thumbUrl,
                    ),
                    provider = id,
                    confidence = 1.0f,
                )
            }
        }

        // Fall back to release-group art
        if (groupId != null) {
            val url = api.getGroupArtworkUrl(groupId, SIZE_FULL)
            if (url != null) {
                val thumbUrl = api.getGroupArtworkUrl(groupId, SIZE_THUMB)
                return EnrichmentResult.Success(
                    type = type,
                    data = EnrichmentData.Artwork(
                        url = url,
                        thumbnailUrl = thumbUrl,
                    ),
                    provider = id,
                    confidence = 1.0f,
                )
            }
        }

        return EnrichmentResult.NotFound(type, id)
    }

    companion object {
        private const val SIZE_FULL = 1200
        private const val SIZE_THUMB = 250
    }
}
