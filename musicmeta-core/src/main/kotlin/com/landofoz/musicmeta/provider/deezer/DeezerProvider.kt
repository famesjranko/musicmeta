package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Enrichment provider using Deezer's public search API.
 * Provides album art, artist discography, and album tracks (no API key needed).
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
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 50),
        ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, priority = 50),
        ProviderCapability(EnrichmentType.ALBUM_TRACKS, priority = 50),
        ProviderCapability(EnrichmentType.ALBUM_METADATA, priority = 50),
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 30),
        ProviderCapability(EnrichmentType.ARTIST_RADIO, priority = 100),
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
        return try {
            when (type) {
                EnrichmentType.ARTIST_DISCOGRAPHY -> enrichDiscography(request)
                EnrichmentType.ALBUM_TRACKS -> enrichAlbumTracks(request)
                EnrichmentType.ALBUM_METADATA -> enrichAlbumMetadata(request, type)
                EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(request)
                EnrichmentType.ARTIST_RADIO -> enrichArtistRadio(request)
                else -> enrichAlbumArt(request, type)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private fun mapError(type: EnrichmentType, e: Exception): EnrichmentResult.Error {
        val kind = when (e) {
            is java.io.IOException -> ErrorKind.NETWORK
            is org.json.JSONException -> ErrorKind.PARSE
            else -> ErrorKind.UNKNOWN
        }
        return EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e, kind)
    }

    private suspend fun enrichDiscography(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_DISCOGRAPHY, id)

        val artist = api.searchArtist(artistRequest.name)
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_DISCOGRAPHY, id)

        val albums = api.getArtistAlbums(artist.id)
        if (albums.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ARTIST_DISCOGRAPHY, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_DISCOGRAPHY,
            data = DeezerMapper.toDiscography(albums),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = false),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
        )
    }

    private suspend fun enrichSimilarArtists(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)

        // Check for cached Deezer artist ID first, fall back to search
        val deezerId = request.identifiers.extra["deezerId"]?.toLongOrNull()
        val artist = if (deezerId != null) {
            DeezerArtistSearchResult(id = deezerId, name = artistRequest.name)
        } else {
            val searchResult = api.searchArtist(artistRequest.name)
                ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)
            // Verify the search result matches the requested artist
            if (!ArtistMatcher.isMatch(artistRequest.name, searchResult.name)) {
                return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)
            }
            searchResult
        }

        val related = api.getRelatedArtists(artist.id)
        if (related.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = DeezerMapper.toSimilarArtists(related),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
        )
    }

    private suspend fun enrichArtistRadio(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)

        // Check for cached Deezer artist ID first, fall back to search
        val deezerId = request.identifiers.extra["deezerId"]?.toLongOrNull()
        val artist = if (deezerId != null) {
            DeezerArtistSearchResult(id = deezerId, name = artistRequest.name)
        } else {
            val searchResult = api.searchArtist(artistRequest.name)
                ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)
            // Verify the search result matches the requested artist
            if (!ArtistMatcher.isMatch(artistRequest.name, searchResult.name)) {
                return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)
            }
            searchResult
        }

        val tracks = api.getArtistRadio(artist.id)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_RADIO,
            data = DeezerMapper.toRadioPlaylist(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
        )
    }

    private suspend fun enrichAlbumTracks(request: EnrichmentRequest): EnrichmentResult {
        val albumRequest = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(EnrichmentType.ALBUM_TRACKS, id)

        val query = "${albumRequest.artist} ${albumRequest.title}"
        val albums = api.searchAlbums(query, 1)
        val album = albums.firstOrNull()
            ?: return EnrichmentResult.NotFound(EnrichmentType.ALBUM_TRACKS, id)

        val tracks = api.getAlbumTracks(album.id)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ALBUM_TRACKS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_TRACKS,
            data = DeezerMapper.toTracklist(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = false),
        )
    }

    private suspend fun enrichAlbumMetadata(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }
        val query = "${request.artist} ${request.title}"
        val results = api.searchAlbums(query, 5)
        val result = results.firstOrNull {
            ArtistMatcher.isMatch(request.artist, it.artistName)
        } ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = DeezerMapper.toAlbumMetadata(result),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
        )
    }

    private suspend fun enrichAlbumArt(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }

        val query = "${request.artist} ${request.title}"
        val results = api.searchAlbums(query, 5)

        val result = results.firstOrNull {
            ArtistMatcher.isMatch(request.artist, it.artistName)
        } ?: return EnrichmentResult.NotFound(type, id)

        val artwork = DeezerMapper.toArtwork(result)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = artwork,
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
        )
    }

    private fun DeezerAlbumResult.toCandidate() =
        DeezerMapper.toSearchCandidate(this, this@DeezerProvider.id, SEARCH_SCORE)

    private companion object {
        const val SEARCH_SCORE = 75
    }
}
