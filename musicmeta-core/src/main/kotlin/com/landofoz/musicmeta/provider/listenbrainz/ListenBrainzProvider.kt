package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentConfig
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
 * Provides popularity and discography data from ListenBrainz.
 * Uses batch POST endpoints for recording and artist popularity,
 * with fallback to top-recordings for artist popularity.
 * Requires a musicBrainzId in request identifiers for most capabilities.
 * When [authToken] is provided, also registers ARTIST_RADIO_DISCOVERY capability.
 */
class ListenBrainzProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
    private val authToken: String? = null,
    private val config: EnrichmentConfig = EnrichmentConfig(),
) : EnrichmentProvider {

    private val api = ListenBrainzApi(httpClient, rateLimiter, authToken)

    override val id: String = "listenbrainz"
    override val displayName: String = "ListenBrainz"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = buildList {
        add(ProviderCapability(
            type = EnrichmentType.ARTIST_POPULARITY,
            priority = PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ))
        add(ProviderCapability(
            type = EnrichmentType.TRACK_POPULARITY,
            priority = FALLBACK_PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ))
        add(ProviderCapability(
            type = EnrichmentType.ARTIST_DISCOGRAPHY,
            priority = FALLBACK_PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ))
        add(ProviderCapability(
            type = EnrichmentType.SIMILAR_ARTISTS,
            priority = FALLBACK_PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ))
        add(ProviderCapability(
            type = EnrichmentType.ARTIST_TOP_TRACKS,
            priority = PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ))
        // ARTIST_RADIO_DISCOVERY only available when authToken is provided (auth gating per-capability)
        if (authToken != null) {
            add(ProviderCapability(
                type = EnrichmentType.ARTIST_RADIO_DISCOVERY,
                priority = PRIORITY,
                identifierRequirement = IdentifierRequirement.NONE,
            ))
        }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        // ARTIST_RADIO_DISCOVERY works with or without MBID
        if (type == EnrichmentType.ARTIST_RADIO_DISCOVERY) {
            return enrichRadioDiscovery(request, type)
        }

        val mbid = request.identifiers.musicBrainzId
        if (mbid.isNullOrBlank()) return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.ARTIST_POPULARITY -> enrichArtistPopularity(mbid, type)
            EnrichmentType.TRACK_POPULARITY -> enrichTrackPopularity(mbid, type)
            EnrichmentType.ARTIST_DISCOGRAPHY -> enrichDiscography(mbid, type)
            EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(mbid, type)
            EnrichmentType.ARTIST_TOP_TRACKS -> enrichTopTracks(mbid, type)
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichArtistPopularity(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val artists = api.getArtistPopularity(listOf(artistMbid))
            if (artists.isNotEmpty()) {
                return success(ListenBrainzMapper.toArtistPopularity(artists), type)
            }
            // Fall back to existing top-recordings approach
            val tracks = api.getTopRecordingsForArtist(artistMbid)
            if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toPopularity(tracks), type)
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichTrackPopularity(
        recordingMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val recordings = api.getRecordingPopularity(listOf(recordingMbid))
            if (recordings.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toTrackPopularity(recordings), type)
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichDiscography(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val groups = api.getTopReleaseGroupsForArtist(artistMbid)
            if (groups.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toDiscography(groups), type)
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichSimilarArtists(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val artists = api.getSimilarArtists(artistMbid)
            if (artists.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toSimilarArtists(artists), type)
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichTopTracks(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val tracks = api.getTopRecordingsForArtist(artistMbid)
            if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toTopTracks(tracks), type)
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichRadioDiscovery(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(type, id)
        return try {
            val prompt = request.identifiers.musicBrainzId ?: artistRequest.name
            val tracks = api.getRadio(prompt, config.radioDiscoveryMode.apiValue)
            if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toRadioPlaylist(tracks), type)
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private fun success(
        data: com.landofoz.musicmeta.EnrichmentData,
        type: EnrichmentType,
    ) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = ConfidenceCalculator.authoritative(),
    )

    private companion object {
        const val PRIORITY = 100
        /** Fallback priority -- Last.fm is primary for track popularity. */
        const val FALLBACK_PRIORITY = 50
    }
}
