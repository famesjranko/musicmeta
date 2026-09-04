package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.SimilarTrack
import com.landofoz.musicmeta.engine.AlbumMatch
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.trustedProviderIdentifier
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enrichment provider using Deezer's public search API.
 * Provides album art, artist discography, and album tracks (no API key needed).
 */
public class DeezerProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter = RateLimiter(100),
    private val radioLimit: Int = 50,
) : EnrichmentProvider {

    private val api = DeezerApi(httpClient, rateLimiter)

    /**
     * This call's [DeezerAlbumScope], shared by ALBUM_TRACKS, ALBUM_METADATA and ALBUM_ART so no
     * two of them search or fetch the same album twice in one `enrich()` fan-out
     * ([ProviderCallScope], `docs/pitfalls.md` §12). Called directly (no engine context), each
     * call gets its own.
     */
    private suspend fun albumScope(): DeezerAlbumScope =
        currentCoroutineContext()[ProviderCallScope]?.slot(this) { DeezerAlbumScope(api) }
            ?: DeezerAlbumScope(api)

    override val id: String = "deezer"
    override val displayName: String = "Deezer"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
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
        ProviderCapability(EnrichmentType.TRACK_METADATA, priority = 70),
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
            EnrichmentType.TRACK_METADATA -> enrichTrackMetadata(request)
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
            resolvedIdentifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, artist.id.toString()),
        )
    }

    private suspend fun enrichTopTracks(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)

        val resolution = resolveArtistByIdOrSearch(artistRequest, EnrichmentType.ARTIST_TOP_TRACKS)
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)
        val artist = resolution.artist

        val tracks = api.getArtistTop(artist.id, limit = 100)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ARTIST_TOP_TRACKS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_TOP_TRACKS,
            data = DeezerMapper.toTopTracks(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, artist.id.toString()),
            provenance = resolution.provenance,
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
            resolvedIdentifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, artist.id.toString()),
        )
    }

    private suspend fun enrichSimilarArtists(request: EnrichmentRequest): EnrichmentResult {
        val artistRequest = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)

        val resolution = resolveArtistByIdOrSearch(artistRequest, EnrichmentType.SIMILAR_ARTISTS)
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)
        val artist = resolution.artist

        val related = api.getRelatedArtists(artist.id)
        if (related.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ARTISTS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = DeezerMapper.toSimilarArtists(related),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, artist.id.toString()),
            provenance = resolution.provenance,
        )
    }

    /**
     * `SIMILAR_TRACKS` is artist-derived, the same approximation [SimilarAlbumsProvider] uses for
     * `SIMILAR_ALBUMS`: Deezer has no track-similarity endpoint (`/track/{id}/radio` doesn't
     * exist), so this takes the seed track's artist id straight off the `/search/track` result
     * (`DeezerTrackSearchResult.artistId`), walks `/artist/{id}/related`, and samples each related
     * artist's `/artist/{id}/top`. Results rank by
     * *artist* similarity applied uniformly to that artist's top tracks, not genuine track-level
     * similarity — see [SimilarTrack.matchScore] KDoc for the consequence.
     *
     * No second `search/artist` round trip: the id the track search already returned is
     * authoritative for the artist the track actually belongs to, where a fresh name search could
     * land on a different, same-named artist. `ArtistMatcher.isMatch` still runs once, against the
     * track search result's own artist name, to validate that the track search itself landed on the
     * right artist.
     *
     * Deliberately does not reuse `request.identifiers.get(IdentifierNamespace.DEEZER)` as a shortcut to the
     * artist id the way [enrichArtistRadio] does: on a `ForTrack` request that key already means
     * the *track's* own Deezer id (written by this method and read by [enrichTrackPreview]), so
     * treating it as an artist id would look up an unrelated artist silently.
     *
     * Passes `trackRequest.album` into [DeezerApi.searchTrack] like [enrichTrackPreview] does: the
     * seed track itself must be the right edition, or the "related artist" derivation below starts
     * from the wrong artist entirely on a title that only the wrong edition happens to share.
     */
    private suspend fun enrichSimilarTracks(request: EnrichmentRequest): EnrichmentResult {
        val trackRequest = request as? EnrichmentRequest.ForTrack
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_TRACKS, id)

        val seedTrack = api.searchTrack(trackRequest.title, trackRequest.artist, trackRequest.album)
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
            resolvedIdentifiers = EnrichmentIdentifiers()
                .with(IdentifierNamespace.DEEZER, seedTrack.id.toString()),
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

        val resolution = resolveArtistByIdOrSearch(artistRequest, EnrichmentType.ARTIST_RADIO)
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)
        val artist = resolution.artist

        val tracks = api.getArtistRadio(artist.id, limit = radioLimit)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ARTIST_RADIO, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_RADIO,
            data = DeezerMapper.toRadioPlaylist(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, artist.id.toString()),
            provenance = resolution.provenance,
        )
    }

    private suspend fun enrichTrackPreview(request: EnrichmentRequest): EnrichmentResult {
        val trackRequest = request as? EnrichmentRequest.ForTrack
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)

        // Use the provider-native route helper; cache identity remains the complete request tuple
        // and does not infer which provider branch will win.
        val trustedId = trustedProviderIdentifier(request, EnrichmentType.TRACK_PREVIEW)?.value?.toLongOrNull()
        val resolution = resolveTrackByIdOrSearch(trackRequest, trustedId)
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)

        val preview = DeezerMapper.toTrackPreview(resolution.track)
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.TRACK_PREVIEW,
            data = preview,
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers()
                .with(IdentifierNamespace.DEEZER, resolution.track.id.toString()),
            provenance = resolution.provenance,
        )
    }

    private suspend fun enrichTrackMetadata(request: EnrichmentRequest): EnrichmentResult {
        val trackRequest = request as? EnrichmentRequest.ForTrack
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_METADATA, id)

        val trustedId = trustedProviderIdentifier(request, EnrichmentType.TRACK_METADATA)?.value?.toLongOrNull()
        val resolution = resolveTrackByIdOrSearch(trackRequest, trustedId)
            ?: return EnrichmentResult.NotFound(EnrichmentType.TRACK_METADATA, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.TRACK_METADATA,
            data = DeezerMapper.toTrackMetadata(resolution.track),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
            resolvedIdentifiers = EnrichmentIdentifiers()
                .with(IdentifierNamespace.DEEZER, resolution.track.id.toString()),
            provenance = resolution.provenance,
        )
    }

    private suspend fun enrichAlbumTracks(request: EnrichmentRequest): EnrichmentResult {
        val albumRequest = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(EnrichmentType.ALBUM_TRACKS, id)

        val album = albumScope().resolveAlbum(albumRequest)?.candidate
            ?: return EnrichmentResult.NotFound(EnrichmentType.ALBUM_TRACKS, id)

        val tracks = api.getAlbumTracks(album.id)
        if (tracks.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.ALBUM_TRACKS, id)

        return EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_TRACKS,
            data = DeezerMapper.toTracklist(tracks),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
        )
    }

    private suspend fun enrichAlbumMetadata(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }
        val scope = albumScope()
        val result = scope.resolveAlbum(request)?.candidate
            ?: return EnrichmentResult.NotFound(type, id)
        val detail = scope.albumDetail(result.id)

        return EnrichmentResult.Success(
            type = type,
            data = DeezerMapper.toAlbumMetadata(result, detail),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
        )
    }

    private suspend fun enrichAlbumArt(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val albumRequest = request.toAlbumArtRequest(type) ?: return EnrichmentResult.NotFound(type, id)

        val result = albumScope().resolveAlbum(albumRequest)?.candidate
            ?: return EnrichmentResult.NotFound(type, id)

        val artwork = DeezerMapper.toArtwork(result)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = artwork,
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
        )
    }

    /**
     * An artist this call resolved and whether it came from a Deezer id already on the request
     * (never a search) — the same fact [enrichTopTracks], [enrichSimilarArtists] and
     * [enrichArtistRadio] each need to self-report [LookupProvenance] truthfully instead of
     * leaving it for [com.landofoz.musicmeta.engine.stampProvenanceOne] to infer from canonical
     * status alone, which cannot see that this call never searched at all.
     */
    private data class ArtistResolution(val artist: DeezerArtistSearchResult, val provenance: LookupProvenance?)

    /**
     * Resolves [request]'s artist for [type] from the trusted Deezer id already on the request
     * (see [trustedProviderIdentifier]), falling back to a name search validated by
     * [ArtistMatcher] — the shared shape behind [enrichTopTracks], [enrichSimilarArtists] and
     * [enrichArtistRadio]. Null on a validated search miss or an unmatched search hit; an id
     * branch is trusted because the scoped identifier is exact provider-owned evidence.
     */
    private suspend fun resolveArtistByIdOrSearch(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): ArtistResolution? {
        val deezerId = trustedProviderIdentifier(request, type)?.value?.toLongOrNull()
        if (deezerId != null) {
            return ArtistResolution(
                DeezerArtistSearchResult(id = deezerId, name = request.name),
                LookupProvenance.PROVIDER_NATIVE_ID,
            )
        }
        val searchResult = api.searchArtist(request.name)
            ?.takeIf { ArtistMatcher.isMatch(request.name, it.name) }
            ?: return null
        return ArtistResolution(searchResult, provenance = null)
    }

    /**
     * A track this call resolved and whether it came from [trustedId] (never a search) — the same
     * fact [enrichTrackPreview] and [enrichTrackMetadata] each need to self-report
     * [LookupProvenance] truthfully. Callers differ only in which identifier they trust as
     * [trustedId] ([enrichTrackPreview] uses the scoped, cache-key-trusted tuple;
     * [enrichTrackMetadata] reads the bare namespace) — everything past that point is identical.
     */
    private data class TrackResolution(val track: DeezerTrackSearchResult, val provenance: LookupProvenance?)

    private suspend fun resolveTrackByIdOrSearch(
        request: EnrichmentRequest.ForTrack,
        trustedId: Long?,
    ): TrackResolution? {
        if (trustedId != null) {
            val track = api.getTrack(trustedId) ?: return null
            return TrackResolution(track, LookupProvenance.PROVIDER_NATIVE_ID)
        }
        val result = api.searchTrack(request.title, request.artist, request.album) ?: return null
        if (!ArtistMatcher.isMatch(request.artist, result.artistName)) return null
        return TrackResolution(result, provenance = null)
    }

    private fun DeezerAlbumResult.toCandidate() =
        DeezerMapper.toSearchCandidate(this, this@DeezerProvider.id, SEARCH_SCORE)

    /**
     * ALBUM_ART's own request shape: a [EnrichmentRequest.ForAlbum] as-is, or a
     * [EnrichmentRequest.ForTrack] carrying a non-blank [EnrichmentRequest.ForTrack.album] treated
     * as one — same artist, and the track's album standing in for the title — since a track-scoped
     * request that already names its album has everything [DeezerAlbumScope.resolveAlbum] needs. A
     * `ForTrack` with no album stays a genuine miss; no other type widens past `ForAlbum`.
     */
    private fun EnrichmentRequest.toAlbumArtRequest(type: EnrichmentType): EnrichmentRequest.ForAlbum? = when {
        this is EnrichmentRequest.ForAlbum -> this
        this is EnrichmentRequest.ForTrack && type == EnrichmentType.ALBUM_ART && !album.isNullOrBlank() ->
            EnrichmentRequest.ForAlbum(identifiers = identifiers, title = album, artist = artist)
        else -> null
    }

    private companion object {
        const val SEARCH_SCORE = 0.75f

        /** Related-artist fan-out bound for SIMILAR_TRACKS — mirrors SimilarAlbumsProvider. */
        private const val RELATED_ARTISTS_LIMIT = 5

        /** Top tracks sampled per related artist — mirrors SimilarAlbumsProvider's per-artist cap. */
        private const val TOP_TRACKS_PER_ARTIST = 3

        /** Cap on the final SIMILAR_TRACKS list — mirrors SimilarAlbumsProvider's SIMILAR_ALBUMS cap. */
        private const val SIMILAR_TRACKS_LIMIT = 20
    }
}

/**
 * One `enrich()` call's album search hit and album-resource detail, held only long enough to
 * serve ALBUM_TRACKS, ALBUM_METADATA and ALBUM_ART from one search and (for ALBUM_METADATA) one
 * `/album/{id}` fetch instead of one search per type — see [DeezerProvider.albumScope]. Dies with
 * the call ([ProviderCallScope]), so a mis-resolved artist/title never outlives a `forceRefresh`.
 */
private class DeezerAlbumScope(private val api: DeezerApi) {

    /** Every field [selectAlbum] reads, so two requests differing only in one still key distinctly. */
    private data class SelectionKey(val artist: String, val title: String, val trackCount: Int?)

    private val searchMutex = Mutex()
    private val searchResults = mutableMapOf<SelectionKey, AlbumMatch<DeezerAlbumResult>?>()

    private val detailMutex = Mutex()
    private val details = mutableMapOf<Long, DeezerAlbum?>()

    /**
     * The accepted-and-ranked search hit for [request], with its selection evidence, one search per
     * distinct complete selection input per call. Selection is [selectAlbum]'s: artist floor, then
     * title tier, then artist quality, then [EnrichmentRequest.ForAlbum.trackCount] evidence — never
     * bare artist-match order. Keyed on every field selection reads, not just artist/title: two
     * requests differing only in `trackCount` must not reuse each other's selection.
     */
    suspend fun resolveAlbum(request: EnrichmentRequest.ForAlbum): AlbumMatch<DeezerAlbumResult>? {
        val key = SelectionKey(request.artist, request.title, request.trackCount)
        return searchMutex.withLock {
            if (searchResults.containsKey(key)) {
                searchResults.getValue(key)
            } else {
                val results = api.searchAlbums("${request.artist} ${request.title}", ALBUM_SEARCH_LIMIT)
                val match = results.selectAlbum(request)
                searchResults[key] = match
                match
            }
        }
    }

    /** The `/album/{id}` resource for [albumId], one fetch per distinct id per call. */
    suspend fun albumDetail(albumId: Long): DeezerAlbum? = detailMutex.withLock {
        if (details.containsKey(albumId)) {
            details.getValue(albumId)
        } else {
            api.getAlbum(albumId).also { details[albumId] = it }
        }
    }

    private companion object {
        /** Candidate pool size for the artist-matched album search — enough hits for the requested edition to surface. */
        const val ALBUM_SEARCH_LIMIT = 5
    }
}
