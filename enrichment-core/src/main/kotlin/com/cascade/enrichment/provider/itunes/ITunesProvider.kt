package com.cascade.enrichment.provider.itunes

import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentIdentifiers
import com.cascade.enrichment.EnrichmentProvider
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.ProviderCapability
import com.cascade.enrichment.SearchCandidate
import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter

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
) : EnrichmentProvider {

    private val api = ITunesApi(httpClient, rateLimiter)

    override val id = "itunes"
    override val displayName = "iTunes"
    override val requiresApiKey = false
    override val isAvailable = true

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 40, requiresIdentifier = false),
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
        val result = try {
            api.searchAlbum(term)
        } catch (e: Exception) {
            return EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }

        if (result?.artworkUrl == null) return EnrichmentResult.NotFound(type, id)

        val highResUrl = result.artworkUrl.replace("100x100bb", "1200x1200bb")

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Artwork(url = highResUrl, thumbnailUrl = result.artworkUrl),
            provider = id,
            confidence = CONFIDENCE,
        )
    }

    private fun ITunesAlbumResult.toCandidate(): SearchCandidate {
        val year = releaseDate?.take(4) // "2003-06-09T07:00:00Z" → "2003"
        return SearchCandidate(
            title = collectionName,
            artist = artistName,
            year = year,
            country = country,
            releaseType = null,
            score = SEARCH_SCORE,
            thumbnailUrl = artworkUrl,
            identifiers = EnrichmentIdentifiers(),
            provider = id,
        )
    }

    private companion object {
        /** Fuzzy search by artist+title. Large catalog but less precise matching than Deezer. */
        const val CONFIDENCE = 0.65f
        const val SEARCH_SCORE = 70
    }
}
