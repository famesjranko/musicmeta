package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarAlbum
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator

/**
 * Discovers albums similar to a seed album by fetching Deezer related artists
 * and sampling their discographies. Scores results by artist similarity rank
 * and era proximity to the seed album's release year.
 *
 * Standalone provider (not composite): all Deezer API calls happen here,
 * not inside a synthesizer.
 */
class SimilarAlbumsProvider(
    private val api: DeezerApi,
) : EnrichmentProvider {

    override val id = "deezer-similar-albums"
    override val displayName = "Deezer Similar Albums"
    override val requiresApiKey = false
    override val isAvailable = true

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ALBUMS, priority = 100),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (type != EnrichmentType.SIMILAR_ALBUMS) return EnrichmentResult.NotFound(type, id)
        return try {
            enrichSimilarAlbums(request)
        } catch (e: Exception) {
            val kind = when (e) {
                is java.io.IOException -> ErrorKind.NETWORK
                is org.json.JSONException -> ErrorKind.PARSE
                else -> ErrorKind.UNKNOWN
            }
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e, kind)
        }
    }

    private suspend fun enrichSimilarAlbums(request: EnrichmentRequest): EnrichmentResult {
        val albumRequest = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        // Resolve seed artist Deezer ID — check cache first, fall back to search
        val deezerId = request.identifiers.extra["deezerId"]?.toLongOrNull()
        val seedArtist = if (deezerId != null) {
            DeezerArtistSearchResult(id = deezerId, name = albumRequest.artist)
        } else {
            val searchResult = api.searchArtist(albumRequest.artist)
                ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)
            if (!ArtistMatcher.isMatch(albumRequest.artist, searchResult.name)) {
                return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)
            }
            searchResult
        }

        // Fetch up to 5 related artists
        val relatedArtists = api.getRelatedArtists(seedArtist.id, limit = 5)
        if (relatedArtists.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        val seedYear = albumRequest.year
        val count = relatedArtists.size.coerceAtLeast(1)

        // For each related artist, fetch up to 3 albums and score them
        val albums = mutableListOf<SimilarAlbum>()
        for ((index, artist) in relatedArtists.withIndex()) {
            val artistScore = 1.0f - (index.toFloat() / count) * 0.9f
            val artistAlbums = api.getArtistAlbums(artist.id, limit = 3)
            for (album in artistAlbums) {
                val eraMultiplier = eraMultiplier(seedYear, album.releaseDate?.take(4)?.toIntOrNull())
                val finalScore = artistScore * eraMultiplier
                albums.add(DeezerMapper.toSimilarAlbum(album, artist.name, finalScore))
            }
        }

        if (albums.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        // Deduplicate by title+artist (case-insensitive), sort by score desc, cap at 20
        val deduped = albums
            .groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .map { (_, dupes) -> dupes.maxByOrNull { it.artistMatchScore } ?: dupes.first() }
            .sortedByDescending { it.artistMatchScore }
            .take(20)

        return EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ALBUMS,
            data = EnrichmentData.SimilarAlbums(deduped),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", seedArtist.id.toString()),
        )
    }

    /**
     * Returns an era proximity multiplier based on how close the album year is
     * to the seed album year. Returns 1.0 when seedYear is null (no era data).
     *
     * Within ±5 years: 1.2x (close era boost)
     * Within ±10 years: 1.0x (neutral)
     * Beyond ±10 years: 0.8x (era penalty)
     */
    private fun eraMultiplier(seedYear: Int?, albumYear: Int?): Float {
        if (seedYear == null || albumYear == null) return 1.0f
        val diff = kotlin.math.abs(seedYear - albumYear)
        return when {
            diff <= 5 -> 1.2f
            diff <= 10 -> 1.0f
            else -> 0.8f
        }
    }
}
