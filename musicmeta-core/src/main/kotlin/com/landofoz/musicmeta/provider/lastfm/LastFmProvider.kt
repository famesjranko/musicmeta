package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarArtist
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
        ProviderCapability(EnrichmentType.TRACK_POPULARITY, priority = 50),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (!isAvailable) return EnrichmentResult.NotFound(type, id)
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(type, id)

        return try {
            when (type) {
                EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(artistRequest, type)
                EnrichmentType.GENRE -> enrichGenre(artistRequest, type)
                EnrichmentType.ARTIST_BIO -> enrichBio(artistRequest, type)
                EnrichmentType.ARTIST_POPULARITY -> enrichPopularity(artistRequest, type)
                EnrichmentType.TRACK_POPULARITY -> enrichPopularity(artistRequest, type)
                else -> EnrichmentResult.NotFound(type, id)
            }
        } catch (e: Exception) {
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }
    }

    private suspend fun enrichSimilarArtists(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val similar = api.getSimilarArtists(request.name)
        if (similar.isEmpty()) return EnrichmentResult.NotFound(type, id)
        val data = EnrichmentData.SimilarArtists(
            artists = similar.map {
                SimilarArtist(
                    name = it.name,
                    musicBrainzId = it.mbid,
                    matchScore = it.matchScore,
                )
            },
        )
        return success(data, type)
    }

    private suspend fun enrichGenre(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val tags = api.getArtistTopTags(request.name)
        if (tags.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return success(EnrichmentData.Metadata(genres = tags), type)
    }

    private suspend fun enrichBio(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val info = api.getArtistInfo(request.name)
            ?: return EnrichmentResult.NotFound(type, id)
        val bio = info.bio ?: return EnrichmentResult.NotFound(type, id)
        return success(EnrichmentData.Biography(text = bio, source = "Last.fm"), type)
    }

    private suspend fun enrichPopularity(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val info = api.getArtistInfo(request.name)
            ?: return EnrichmentResult.NotFound(type, id)
        return success(
            EnrichmentData.Popularity(
                listenerCount = info.listeners,
                listenCount = info.playcount,
            ),
            type,
        )
    }

    private fun success(data: EnrichmentData, type: EnrichmentType) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = CONFIDENCE,
    )

    private companion object {
        /** Artist name match. Good data quality from large user base, but user-contributed. */
        const val CONFIDENCE = 0.85f
    }
}
