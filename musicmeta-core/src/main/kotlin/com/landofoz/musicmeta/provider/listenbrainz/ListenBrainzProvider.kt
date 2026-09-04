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
 * Provides popularity, discography and artist-similarity data from ListenBrainz.
 * Uses batch POST endpoints for recording and artist popularity, and top-recordings for top tracks.
 * Requires a musicBrainzId in request identifiers for most capabilities.
 * When [authToken] is provided, also registers ARTIST_RADIO_DISCOVERY capability.
 *
 * SIMILAR_ARTISTS comes from the experimental Labs host rather than the main API — a separate
 * deployment with its own availability, and a route whose `algorithm` parameter this library pins
 * to one value. See [ListenBrainzApi.SIMILAR_ARTISTS_ALGORITHM].
 */
public class ListenBrainzProvider(
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
            type = EnrichmentType.ARTIST_TOP_TRACKS,
            priority = PRIORITY,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ))
        add(ProviderCapability(
            type = EnrichmentType.SIMILAR_ARTISTS,
            priority = FALLBACK_PRIORITY,
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
            EnrichmentType.ARTIST_TOP_TRACKS -> enrichTopTracks(mbid, type)
            EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(mbid, type)
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichArtistPopularity(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val artists = api.getArtistPopularity(listOf(artistMbid))
            if (artists.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toArtistPopularity(artists), type)
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

    /**
     * Similar artists from the Labs host, which is a separate deployment from the main API and can
     * be down or refuse the request while every other capability here answers.
     *
     * An empty list is `NotFound` — the route answers an artist it holds no similarity data for
     * with an empty array, and thin data for a long-tail artist is a short list, not a failure. A
     * refusal is an `Error`, thrown from the api client rather than reaching here as an empty list.
     */
    private suspend fun enrichSimilarArtists(
        artistMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val similar = api.getSimilarArtists(artistMbid)
            if (similar.isEmpty()) return EnrichmentResult.NotFound(type, id)
            success(ListenBrainzMapper.toSimilarArtists(similar), type)
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

        /** Fallback priority -- Last.fm is primary for track popularity and for artist similarity. */
        const val FALLBACK_PRIORITY = 50
    }
}
