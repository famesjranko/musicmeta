package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ArtistMatcher
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
        val results = try {
            api.searchAlbums(term, 5)
        } catch (e: Exception) {
            return EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }

        // Pick the first result whose artist matches the request
        val result = results.firstOrNull {
            ArtistMatcher.isMatch(request.artist, it.artistName)
        }

        if (result?.artworkUrl == null) return EnrichmentResult.NotFound(type, id)

        val highResUrl = result.artworkUrl.replace("100x100bb", "${artworkSize}x${artworkSize}bb")

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

    companion object {
        const val DEFAULT_ARTWORK_SIZE = 1200
        /** Fuzzy search by artist+title. Large catalog but less precise matching than Deezer. */
        private const val CONFIDENCE = 0.65f
        private const val SEARCH_SCORE = 70
    }
}
