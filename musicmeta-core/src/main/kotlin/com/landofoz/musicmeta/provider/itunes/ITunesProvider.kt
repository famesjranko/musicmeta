package com.landofoz.musicmeta.provider.itunes

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
 * Enrichment provider using Apple's iTunes Search and Lookup APIs.
 * Provides album art, album metadata, album tracks, and artist discography.
 * No API key needed.
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
        ProviderCapability(EnrichmentType.ALBUM_TRACKS, priority = 30),
        ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, priority = 30),
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
        return when (type) {
            EnrichmentType.ALBUM_TRACKS -> enrichAlbumTracks(request, type)
            EnrichmentType.ARTIST_DISCOGRAPHY -> enrichArtistDiscography(request, type)
            else -> enrichAlbumType(request, type)
        }
    }

    private suspend fun enrichAlbumTracks(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }

        return try {
            // Try direct lookup if collectionId is already stored
            val collectionId = request.identifiers.get("itunesCollectionId")?.toLongOrNull()
            if (collectionId != null) {
                val tracks = api.lookupAlbumTracks(collectionId)
                if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
                return EnrichmentResult.Success(
                    type = type,
                    data = ITunesMapper.toTracklist(tracks),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }

            // Fall back to search then lookup
            val term = "${request.artist} ${request.title}"
            val results = api.searchAlbums(term, 5)
            val albumResult = results.firstOrNull {
                ArtistMatcher.isMatch(request.artist, it.artistName)
            } ?: return EnrichmentResult.NotFound(type, id)

            val searchedCollectionId = albumResult.collectionId.takeIf { it > 0 }
                ?: return EnrichmentResult.NotFound(type, id)

            val tracks = api.lookupAlbumTracks(searchedCollectionId)
            if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)

            val resolvedIdentifiers = buildResolvedIdentifiers(albumResult)
            EnrichmentResult.Success(
                type = type,
                data = ITunesMapper.toTracklist(tracks),
                provider = id,
                confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
                resolvedIdentifiers = resolvedIdentifiers,
            )
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichArtistDiscography(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForArtist) {
            return EnrichmentResult.NotFound(type, id)
        }

        return try {
            // Try direct lookup if artistId is already stored
            val artistId = request.identifiers.get("itunesArtistId")?.toLongOrNull()
                ?: api.searchArtist(request.name)
                ?: return EnrichmentResult.NotFound(type, id)

            val albums = api.lookupArtistAlbums(artistId)
            if (albums.isEmpty()) return EnrichmentResult.NotFound(type, id)

            EnrichmentResult.Success(
                type = type,
                data = ITunesMapper.toDiscography(albums),
                provider = id,
                confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            )
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichAlbumType(
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
            return mapError(type, e)
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

    private fun buildResolvedIdentifiers(result: ITunesAlbumResult): EnrichmentIdentifiers? {
        val collectionId = result.collectionId.takeIf { it > 0 } ?: return null
        return EnrichmentIdentifiers().withExtra("itunesCollectionId", collectionId.toString())
    }

    private fun ITunesAlbumResult.toCandidate(): SearchCandidate =
        ITunesMapper.toSearchCandidate(this, id, SEARCH_SCORE)

    private fun mapError(type: EnrichmentType, e: Exception): EnrichmentResult.Error {
        val kind = when (e) {
            is java.io.IOException -> ErrorKind.NETWORK
            is org.json.JSONException -> ErrorKind.PARSE
            else -> ErrorKind.UNKNOWN
        }
        return EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e, kind)
    }

    companion object {
        const val DEFAULT_ARTWORK_SIZE = 1200
        private const val SEARCH_SCORE = 70
    }
}
