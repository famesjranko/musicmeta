package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Enrichment provider using Deezer's public search API.
 * Provides album art as a search-based fallback (no API key needed).
 */
class DeezerProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter = RateLimiter(100),
) : EnrichmentProvider {

    private val api = DeezerApi(httpClient, rateLimiter)

    override val id = "deezer"
    override val displayName = "Deezer"
    override val requiresApiKey = false
    override val isAvailable = true

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 50, requiresIdentifier = false),
    )

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> {
        if (request !is EnrichmentRequest.ForAlbum) return emptyList()
        val query = "${request.artist} ${request.title}"
        return try {
            api.searchAlbums(query, limit).map { it.toCandidate() }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }

        val query = "${request.artist} ${request.title}"
        val result = try {
            api.searchAlbum(query)
        } catch (e: Exception) {
            return EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }

        if (result == null) return EnrichmentResult.NotFound(type, id)

        val artworkUrl = result.coverXl ?: result.coverBig
            ?: result.coverMedium ?: result.coverSmall
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Artwork(url = artworkUrl, thumbnailUrl = result.coverMedium),
            provider = id,
            confidence = CONFIDENCE,
        )
    }

    private fun DeezerAlbumResult.toCandidate() = SearchCandidate(
        title = title,
        artist = artistName,
        year = null,
        country = null,
        releaseType = null,
        score = SEARCH_SCORE,
        thumbnailUrl = coverMedium ?: coverSmall,
        identifiers = EnrichmentIdentifiers(),
        provider = this@DeezerProvider.id,
    )

    private companion object {
        /** Fuzzy search by artist+title against large catalog. First result may not be exact match. */
        const val CONFIDENCE = 0.7f
        const val SEARCH_SCORE = 75
    }
}
