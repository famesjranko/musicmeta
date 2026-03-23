package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Last.fm enrichment provider. Supplies similar artists, genre tags,
 * artist bios, and popularity data. Requires a Last.fm API key.
 * Only handles artist-level requests.
 */
class LastFmProvider(
    private val apiKeyProvider: () -> String,
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    constructor(apiKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ apiKey }, httpClient, rateLimiter)

    private val api = LastFmApi(apiKeyProvider, httpClient, rateLimiter)

    override val id = "lastfm"
    override val displayName = "Last.fm"
    override val requiresApiKey = true
    override val isAvailable: Boolean get() = apiKeyProvider().isNotBlank()

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100),
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        ProviderCapability(EnrichmentType.ARTIST_BIO, priority = 50),
        ProviderCapability(EnrichmentType.ARTIST_POPULARITY, priority = 100),
        ProviderCapability(EnrichmentType.ARTIST_TOP_TRACKS, priority = 100),
        ProviderCapability(EnrichmentType.SIMILAR_TRACKS, priority = 100),
        ProviderCapability(EnrichmentType.TRACK_POPULARITY, priority = 100),
        ProviderCapability(EnrichmentType.ALBUM_METADATA, priority = 40),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (!isAvailable) return EnrichmentResult.NotFound(type, id)

        // ALBUM_METADATA requires a ForAlbum request
        if (type == EnrichmentType.ALBUM_METADATA) {
            val albumRequest = request as? EnrichmentRequest.ForAlbum
                ?: return EnrichmentResult.NotFound(type, id)
            return try {
                enrichAlbumMetadata(albumRequest, type)
            } catch (e: Exception) {
                mapError(type, e)
            }
        }

        // SIMILAR_TRACKS and TRACK_POPULARITY require a ForTrack request; all others require ForArtist
        if (type == EnrichmentType.SIMILAR_TRACKS || type == EnrichmentType.TRACK_POPULARITY) {
            val trackRequest = request as? EnrichmentRequest.ForTrack
                ?: return EnrichmentResult.NotFound(type, id)
            return try {
                when (type) {
                    EnrichmentType.SIMILAR_TRACKS -> enrichSimilarTracks(trackRequest, type)
                    EnrichmentType.TRACK_POPULARITY -> enrichTrackPopularity(trackRequest, type)
                    else -> EnrichmentResult.NotFound(type, id)
                }
            } catch (e: Exception) {
                mapError(type, e)
            }
        }

        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(type, id)

        return try {
            when (type) {
                EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(artistRequest, type)
                EnrichmentType.GENRE -> enrichGenre(artistRequest, type)
                EnrichmentType.ARTIST_BIO -> enrichBio(artistRequest, type)
                EnrichmentType.ARTIST_POPULARITY -> enrichPopularity(artistRequest, type)
                EnrichmentType.ARTIST_TOP_TRACKS -> enrichTopTracks(artistRequest, type)
                else -> EnrichmentResult.NotFound(type, id)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichSimilarArtists(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val similar = api.getSimilarArtists(request.name)
        if (similar.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toSimilarArtists(similar), type)
    }

    private suspend fun enrichGenre(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val tags = api.getArtistTopTags(request.name)
        if (tags.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toGenre(tags), type)
    }

    private suspend fun enrichBio(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val info = api.getArtistInfo(request.name)
            ?: return EnrichmentResult.NotFound(type, id)
        val bio = info.bio ?: return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toBiography(bio), type)
    }

    private suspend fun enrichSimilarTracks(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        val tracks = api.getSimilarTracks(request.title, request.artist)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toSimilarTracks(tracks), type)
    }

    private suspend fun enrichTrackPopularity(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        val info = api.getTrackInfo(request.title, request.artist)
            ?: return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toTrackPopularity(info), type)
    }

    private suspend fun enrichPopularity(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val info = api.getArtistInfo(request.name)
            ?: return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toPopularity(info), type)
    }

    private suspend fun enrichTopTracks(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val tracks = api.getArtistTopTracks(request.name, limit = 1000)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(LastFmMapper.toTopTracks(tracks), type)
    }

    private suspend fun enrichAlbumMetadata(
        request: EnrichmentRequest.ForAlbum,
        type: EnrichmentType,
    ): EnrichmentResult {
        val info = api.getAlbumInfo(request.title, request.artist)
            ?: return EnrichmentResult.NotFound(type, id)
        val metadata = LastFmMapper.toAlbumMetadata(info)
        if (metadata.genres.isNullOrEmpty() && metadata.trackCount == null) {
            return EnrichmentResult.NotFound(type, id)
        }
        return success(metadata, type)
    }

    private fun success(data: EnrichmentData, type: EnrichmentType) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
    )
}
