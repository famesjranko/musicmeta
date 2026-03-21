package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Provides popularity and discography data from ListenBrainz.
 * Uses batch POST endpoints for recording and artist popularity,
 * with fallback to top-recordings for artist popularity.
 * Requires a musicBrainzId in request identifiers.
 */
class ListenBrainzProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    private val api = ListenBrainzApi(httpClient, rateLimiter)

    override val id: String = "listenbrainz"
    override val displayName: String = "ListenBrainz"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.ARTIST_POPULARITY,
            priority = PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.TRACK_POPULARITY,
            priority = FALLBACK_PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val mbid = request.identifiers.musicBrainzId
        if (mbid.isNullOrBlank()) return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.ARTIST_POPULARITY -> enrichArtistPopularity(mbid, type)
            EnrichmentType.TRACK_POPULARITY -> enrichTrackPopularity(mbid, type)
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichArtistPopularity(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        val artists = api.getArtistPopularity(listOf(artistMbid))
        if (artists.isNotEmpty()) {
            return success(ListenBrainzMapper.toArtistPopularity(artists), type)
        }
        // Fall back to existing top-recordings approach
        val tracks = api.getTopRecordingsForArtist(artistMbid)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(ListenBrainzMapper.toPopularity(tracks), type)
    }

    private suspend fun enrichTrackPopularity(
        recordingMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        val recordings = api.getRecordingPopularity(listOf(recordingMbid))
        if (recordings.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(ListenBrainzMapper.toTrackPopularity(recordings), type)
    }

    private fun success(
        data: com.landofoz.musicmeta.EnrichmentData,
        type: EnrichmentType,
    ) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = CONFIDENCE,
    )

    private companion object {
        const val PRIORITY = 100
        /** Fallback priority -- Last.fm is primary for track popularity. */
        const val FALLBACK_PRIORITY = 50
        /** MBID-based lookup. Reliable but smaller user base than Last.fm. */
        const val CONFIDENCE = 0.85f
    }
}
