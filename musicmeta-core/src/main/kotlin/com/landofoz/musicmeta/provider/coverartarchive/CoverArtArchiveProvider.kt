package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Enrichment provider for album cover art from the Cover Art Archive.
 * Requires a MusicBrainz release or release-group ID.
 */
class CoverArtArchiveProvider(
    httpClient: HttpClient,
    @Suppress("UNUSED_PARAMETER") rateLimiter: RateLimiter,
    private val artworkSize: Int = DEFAULT_ARTWORK_SIZE,
    private val thumbnailSize: Int = DEFAULT_THUMBNAIL_SIZE,
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
            val url = api.getArtworkUrl(releaseId, artworkSize)
            if (url != null) {
                val thumbUrl = api.getArtworkUrl(releaseId, thumbnailSize)
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
            val url = api.getGroupArtworkUrl(groupId, artworkSize)
            if (url != null) {
                val thumbUrl = api.getGroupArtworkUrl(groupId, thumbnailSize)
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
        const val DEFAULT_ARTWORK_SIZE = 1200
        const val DEFAULT_THUMBNAIL_SIZE = 250
    }
}
