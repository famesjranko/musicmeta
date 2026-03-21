package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Enrichment provider using Apple's iTunes Search API.
 * Provides album art as a search-based fallback (no API key needed).
 *
 * Artwork URL trick: iTunes returns 100x100 thumbnails, but replacing
 * "100x100bb" with "1200x1200bb" gives high-resolution images.
 */
class ITunesProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter = RateLimiter(3000),
    private val artworkSize: Int = DEFAULT_ARTWORK_SIZE,
) : EnrichmentProvider {

    private val api = ITunesApi(httpClient, rateLimiter)

    override val id = "itunes"
    override val displayName = "iTunes"
    override val requiresApiKey = false
    override val isAvailable = true

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 40),
        ProviderCapability(EnrichmentType.ALBUM_METADATA, priority = 30),
    )

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> {
        if (request !is EnrichmentRequest.ForAlbum) return emptyList()
        val term = "${request.artist} ${request.title}"
        return try {
            api.searchAlbums(term, limit).map { it.toCandidate() }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }

        val term = "${request.artist} ${request.title}"
        val results = try {
            api.searchAlbums(term, 5)
        } catch (e: Exception) {
            return EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }

        val result = results.firstOrNull {
            ArtistMatcher.isMatch(request.artist, it.artistName)
        } ?: return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.ALBUM_METADATA -> enrichAlbumMetadata(result, type)
            else -> enrichAlbumArt(result, type)
        }
    }

    private fun enrichAlbumMetadata(
        result: ITunesAlbumResult,
        type: EnrichmentType,
    ): EnrichmentResult = EnrichmentResult.Success(
        type = type,
        data = ITunesMapper.toAlbumMetadata(result),
        provider = id,
        confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
    )

    private fun enrichAlbumArt(
        result: ITunesAlbumResult,
        type: EnrichmentType,
    ): EnrichmentResult {
        val artwork = ITunesMapper.toArtwork(result, artworkSize)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = artwork,
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
        )
    }

    private fun ITunesAlbumResult.toCandidate(): SearchCandidate =
        ITunesMapper.toSearchCandidate(this, id, SEARCH_SCORE)

    companion object {
        const val DEFAULT_ARTWORK_SIZE = 1200
        private const val SEARCH_SCORE = 70
    }
}
