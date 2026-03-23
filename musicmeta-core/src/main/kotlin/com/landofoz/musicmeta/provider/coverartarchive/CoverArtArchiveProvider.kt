package com.landofoz.musicmeta.provider.coverartarchive

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
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.ALBUM_ART_BACK,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.ALBUM_BOOKLET,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.CD_ART,
            priority = 50,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
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

            when (type) {
                EnrichmentType.ALBUM_ART -> findArtwork(releaseId, groupId, type)
                EnrichmentType.ALBUM_ART_BACK -> findImageByType(releaseId, type, "Back")
                EnrichmentType.ALBUM_BOOKLET -> findImageByType(releaseId, type, "Booklet")
                EnrichmentType.CD_ART -> findImageByType(releaseId, type, "Medium")
                else -> EnrichmentResult.NotFound(type, id)
            }
        } catch (e: Exception) {
            mapError(type, e)
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
                val frontImage = fetchFrontImage(releaseId)
                return EnrichmentResult.Success(
                    type = type,
                    data = CoverArtArchiveMapper.toArtwork(url, thumbUrl, frontImage),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
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
                    data = CoverArtArchiveMapper.toArtwork(url, thumbUrl),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }
        }

        return EnrichmentResult.NotFound(type, id)
    }

    /** Find an image by its CAA type (e.g., "Back", "Booklet") from metadata. */
    private suspend fun findImageByType(
        releaseId: String?,
        type: EnrichmentType,
        imageType: String,
    ): EnrichmentResult {
        if (releaseId == null) return EnrichmentResult.NotFound(type, id)
        val metadata = api.getArtworkMetadata(releaseId)
            ?: return EnrichmentResult.NotFound(type, id)
        val image = metadata.images.firstOrNull { imageType in it.types }
            ?: return EnrichmentResult.NotFound(type, id)
        return EnrichmentResult.Success(
            type = type,
            data = CoverArtArchiveMapper.toArtwork(image.url, image.thumbnails["small"], image),
            provider = id,
            confidence = ConfidenceCalculator.idBasedLookup(),
        )
    }

    /** Fetch image metadata for sizes. Returns the first front image, or null. */
    private suspend fun fetchFrontImage(releaseId: String): CoverArtArchiveImage? {
        val metadata = api.getArtworkMetadata(releaseId) ?: return null
        return metadata.images.firstOrNull { it.front }
    }

    companion object {
        const val DEFAULT_ARTWORK_SIZE = 1200
        const val DEFAULT_THUMBNAIL_SIZE = 250
    }
}
