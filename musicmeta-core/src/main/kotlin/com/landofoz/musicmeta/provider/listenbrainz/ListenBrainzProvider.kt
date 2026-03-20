package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.PopularTrack
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Provides track popularity data from ListenBrainz.
 * Requires an artist musicBrainzId in the request identifiers.
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
            requiresIdentifier = true,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val artistMbid = request.identifiers.musicBrainzId
        if (artistMbid.isNullOrBlank()) {
            return EnrichmentResult.NotFound(type, id)
        }

        val tracks = api.getTopRecordingsForArtist(artistMbid)
        if (tracks.isEmpty()) {
            return EnrichmentResult.NotFound(type, id)
        }

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Popularity(
                topTracks = tracks.mapIndexed { index, track ->
                    PopularTrack(
                        title = track.title,
                        musicBrainzId = track.recordingMbid,
                        listenCount = track.listenCount,
                        rank = index + 1,
                    )
                },
            ),
            provider = id,
            confidence = CONFIDENCE,
        )
    }

    private companion object {
        const val PRIORITY = 100
        /** MBID-based lookup. Reliable but smaller user base than Last.fm. */
        const val CONFIDENCE = 0.85f
    }
}
