package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.SimilarTrack
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Enrichment provider using Deezer's public search API.
 * Provides album art, artist discography, and album tracks (no API key needed).
 */
class DeezerProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter = RateLimiter(100),
    private val radioLimit: Int = 50,
) : EnrichmentProvider {

    private val api = DeezerApi(httpClient, rateLimiter)

    override val id = "deezer"
    override val displayName = "Deezer"
    override val requiresApiKey = false
    override val isAvailable = true

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ARTIST_PHOTO, priority = 60),
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 50),
        ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, priority = 50),
        ProviderCapability(EnrichmentType.ALBUM_TRACKS, priority = 50),
        ProviderCapability(EnrichmentType.ALBUM_METADATA, priority = 50),
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 30),
        ProviderCapability(EnrichmentType.SIMILAR_TRACKS, priority = 50),
        ProviderCapability(EnrichmentType.ARTIST_RADIO, priority = 100),
        ProviderCapability(EnrichmentType.ARTIST_TOP_TRACKS, priority = 50),
        ProviderCapability(EnrichmentType.TRACK_PREVIEW, priority = 100),
    )

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> {
        if (request !is EnrichmentRequest.ForAlbum) return emptyList()
        val query = "${request.artist} ${request.title}"
        return try {
            api.searchAlbums(query, limit).map { it.toCandidate() }
        } catch (_: Exception) {
            // A suspend call, and emptyList() returns without suspending again, so a cancelled
            // caller would otherwise be told the search simply found nothing. (#53)
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult = try {
        when (type) {
            EnrichmentType.ARTIST_PHOTO -> enrichArtistPhoto(request)
            EnrichmentType.ARTIST_TOP_TRACKS -> enrichTopTracks(request)
            EnrichmentType.ARTIST_DISCOGRAPHY -> enrichDiscography(request)
            EnrichmentType.ALBUM_TRACKS -> enrichAlbumTracks(request)
            EnrichmentType.ALBUM_METADATA -> enrichAlbumMetadata(request, type)
            EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(request)
            EnrichmentType.SIMILAR_TRACKS -> enrichSimilarTracks(request)
            EnrichmentType.ARTIST_RADIO -> enrichArtistRadio(request)
            EnrichmentType.TRACK_PREVIEW -> enrichTrackPreview(request)
            else -> enrichAlbumArt(request, type)
        }
    } catch (e: Exception) {
        mapError(type, e)
    }

    private suspend fun enrichArtistPhoto(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_PHOTO, id)

        val artist = api.searchArtist(artistRequest.name)
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_PHOTO, id)

        if (!ArtistMatcher.isMatch(artistRequest.name, artist.name)) {
            return EnrichmentResult.NotFound(EnrichmentType.ARTIST_PHOTO, id)
        }

        val artwork = DeezerMapper.toArtistPhoto(artist)
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_PHOTO, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_PHOTO,
            data = artwork,
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
        )
    }

    private suspend fun enrichTopTracks(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)

        val deezerId = request.identifiers.extra["deezerId"]?.toLongOrNull()
        val artist = if (deezerId != null) {
            DeezerArtistSearchResult(id = deezerId, name = artistRequest.name)
        } else {
            val searchResult = api.searchArtist(artistRequest.name)
                ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)
            if (!ArtistMatcher.isMatch(artistRequest.name, searchResult.name)) {
                return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)
            }
            searchResult
        }

        val tracks = api.getArtistTop(artist.id, limit = 100)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_TOP_TRACKS,
            data = DeezerMapper.toTopTracks(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
        )
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

    /**
     * `SIMILAR_TRACKS` is artist-derived, the same approximation [SimilarAlbumsProvider] uses for
     * `SIMILAR_ALBUMS`: Deezer has no track-similarity endpoint (`/track/{id}/radio` doesn't exist —
     * see `.scratch/provider-code-findings/issues/16-...`), so this takes the seed track's artist id
     * straight off the `/search/track` result (`DeezerTrackSearchResult.artistId`), walks
     * `/artist/{id}/related`, and samples each related artist's `/artist/{id}/top`. Results rank by
     * *artist* similarity applied uniformly to that artist's top tracks, not genuine track-level
     * similarity — see [SimilarTrack.matchScore] KDoc for the consequence.
     *
     * No second `search/artist` round trip: the id the track search already returned is
     * authoritative for the artist the track actually belongs to, where a fresh name search could
     * land on a different, same-named artist. `ArtistMatcher.isMatch` still runs once, against the
     * track search result's own artist name, to validate that the track search itself landed on the
     * right artist.
     *
     * Deliberately does not reuse `request.identifiers.extra["deezerId"]` as a shortcut to the
     * artist id the way [enrichArtistRadio] does: on a `ForTrack` request that key already means
     * the *track's* own Deezer id (written by this method and read by [enrichTrackPreview]), so
     * treating it as an artist id would look up an unrelated artist silently.
     */
    private suspend fun enrichSimilarTracks(request: EnrichmentRequest): EnrichmentResult {
        val trackRequest = request as? EnrichmentRequest.ForTrack
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)

        val seedTrack = api.searchTrack(trackRequest.title, trackRequest.artist)
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)

        if (!ArtistMatcher.isMatch(trackRequest.artist, seedTrack.artistName)) {
            return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)
        }

        // No name-search fallback: Deezer always populates artist.id on a track search result, so
        // a missing id is treated as absent data rather than silently re-resolved by name.
        val seedArtistId = seedTrack.artistId
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)

        // Fetch up to 5 related artists — same fan-out bound as SimilarAlbumsProvider.
        val relatedArtists = api.getRelatedArtists(seedArtistId, limit = RELATED_ARTISTS_LIMIT)
        if (relatedArtists.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)

        val tracks = similarTracksFromRelatedArtists(relatedArtists, seedTrack)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(dedupeSimilarTracks(tracks)),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", seedTrack.id.toString()),
        )
    }

    /**
     * Samples up to [TOP_TRACKS_PER_ARTIST] top tracks from each of [relatedArtists], scored by
     * that artist's similarity rank, excluding [seedTrack] by Deezer id and by title+artist (in
     * case it resurfaces as a cover/duplicate title under a different id).
     */
    private suspend fun similarTracksFromRelatedArtists(
        relatedArtists: List<DeezerRelatedArtist>,
        seedTrack: DeezerTrackSearchResult,
    ): List<SimilarTrack> {
        val count = relatedArtists.size.coerceAtLeast(1)
        val seedKey = similarTrackKey(seedTrack.title, seedTrack.artistName)
        return relatedArtists.withIndex().flatMap { (index, artist) ->
            val artistScore = 1.0f - (index.toFloat() / count) * 0.9f
            api.getArtistTop(artist.id, limit = TOP_TRACKS_PER_ARTIST)
                .filterNot { it.id == seedTrack.id || similarTrackKey(it.title, it.artistName) == seedKey }
                .map { DeezerMapper.toSimilarTrack(it, artistScore) }
        }
    }

    /**
     * Deduplicates by title+artist (case-insensitive, e.g. two related artists sharing a feature
     * track), sorts by score desc, and caps at 20 — mirrors SimilarAlbumsProvider.
     */
    private fun dedupeSimilarTracks(tracks: List<SimilarTrack>): List<SimilarTrack> =
        tracks
            .groupBy { similarTrackKey(it.title, it.artist) }
            .map { (_, dupes) -> dupes.maxByOrNull { it.matchScore } ?: dupes.first() }
            .sortedByDescending { it.matchScore }
            .take(SIMILAR_TRACKS_LIMIT)

    private fun similarTrackKey(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

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

        val tracks = api.getArtistRadio(artist.id, limit = radioLimit)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_RADIO,
            data = DeezerMapper.toRadioPlaylist(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
        )
    }

    private suspend fun enrichTrackPreview(request: EnrichmentRequest): EnrichmentResult {
        val trackRequest = request as? EnrichmentRequest.ForTrack
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)

        val deezerId = request.identifiers.extra["deezerId"]?.toLongOrNull()
        val trackResult = if (deezerId != null) {
            api.getTrack(deezerId)
        } else {
            val result = api.searchTrack(trackRequest.title, trackRequest.artist)
                ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)
            if (!ArtistMatcher.isMatch(trackRequest.artist, result.artistName)) {
                return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)
            }
            result
        } ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)

        val preview = DeezerMapper.toTrackPreview(trackResult)
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.TRACK_PREVIEW,
            data = preview,
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", trackResult.id.toString()),
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

        /** Related-artist fan-out bound for SIMILAR_TRACKS — mirrors SimilarAlbumsProvider. */
        private const val RELATED_ARTISTS_LIMIT = 5

        /** Top tracks sampled per related artist — mirrors SimilarAlbumsProvider's per-artist cap. */
        private const val TOP_TRACKS_PER_ARTIST = 3

        /** Cap on the final SIMILAR_TRACKS list — mirrors SimilarAlbumsProvider's SIMILAR_ALBUMS cap. */
        private const val SIMILAR_TRACKS_LIMIT = 20
    }
}
