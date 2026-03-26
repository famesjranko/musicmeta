package com.landofoz.musicmeta

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Convenience extension: enriches an artist and returns a structured [ArtistProfile].
 *
 * @param identifiers Pre-resolved identifiers (e.g., from a previous enrichment). When provided,
 *   providers can skip search/identity resolution for identifiers they recognise (e.g., deezerId).
 * @param types Override the default type set to request fewer (or more) types.
 * @param forceRefresh When true, bypasses the cache and fetches fresh data from providers.
 */
suspend fun EnrichmentEngine.artistProfile(
    name: String,
    mbid: String? = null,
    identifiers: EnrichmentIdentifiers? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ARTIST_TYPES,
    forceRefresh: Boolean = false,
): ArtistProfile {
    val results = enrich(
        EnrichmentRequest.forArtist(name, mbid, identifiers = identifiers),
        types,
        forceRefresh,
    )
    return ArtistProfile(name, results)
}

/** Re-enrich from a [SearchCandidate] (e.g., after a "did you mean?" pick). */
suspend fun EnrichmentEngine.artistProfile(
    candidate: SearchCandidate,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ARTIST_TYPES,
    forceRefresh: Boolean = false,
): ArtistProfile = artistProfile(
    candidate.title,
    candidate.identifiers.musicBrainzId,
    identifiers = candidate.identifiers,
    types,
    forceRefresh,
)

/**
 * Convenience extension: enriches an album and returns a structured [AlbumProfile].
 */
suspend fun EnrichmentEngine.albumProfile(
    title: String,
    artist: String,
    mbid: String? = null,
    identifiers: EnrichmentIdentifiers? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ALBUM_TYPES,
    forceRefresh: Boolean = false,
): AlbumProfile {
    val results = enrich(
        EnrichmentRequest.forAlbum(title, artist, mbid, identifiers = identifiers),
        types,
        forceRefresh,
    )
    return AlbumProfile(title, artist, results)
}

/** Re-enrich from a [SearchCandidate]. */
suspend fun EnrichmentEngine.albumProfile(
    candidate: SearchCandidate,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ALBUM_TYPES,
    forceRefresh: Boolean = false,
): AlbumProfile = albumProfile(
    candidate.title,
    candidate.artist ?: "",
    candidate.identifiers.musicBrainzId,
    identifiers = candidate.identifiers,
    types,
    forceRefresh,
)

/**
 * Convenience extension: enriches a track and returns a structured [TrackProfile].
 *
 * @param identifiers Pre-resolved identifiers (e.g., with deezerId from a top track).
 *   When deezerId is present, the Deezer provider skips search and fetches directly by ID.
 */
suspend fun EnrichmentEngine.trackProfile(
    title: String,
    artist: String,
    album: String? = null,
    mbid: String? = null,
    identifiers: EnrichmentIdentifiers? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_TRACK_TYPES,
    forceRefresh: Boolean = false,
): TrackProfile {
    val results = enrich(
        EnrichmentRequest.forTrack(title, artist, album, mbid = mbid, identifiers = identifiers),
        types,
        forceRefresh,
    )
    return TrackProfile(title, artist, results)
}

/** Re-enrich from a [SearchCandidate]. */
suspend fun EnrichmentEngine.trackProfile(
    candidate: SearchCandidate,
    album: String? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_TRACK_TYPES,
    forceRefresh: Boolean = false,
): TrackProfile = trackProfile(
    candidate.title,
    candidate.artist ?: "",
    album,
    candidate.identifiers.musicBrainzId,
    identifiers = candidate.identifiers,
    types,
    forceRefresh,
)

/** Input for [resolveTrackPreviews]. */
data class TrackPreviewRequest(
    val title: String,
    val artist: String,
    val album: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

/** Output from [resolveTrackPreviews]. */
data class TrackPreviewResult(
    val title: String,
    val artist: String,
    val preview: EnrichmentData.TrackPreview?,
)

/**
 * Resolves preview URLs for multiple tracks concurrently.
 *
 * Tracks with a `deezerId` in [EnrichmentIdentifiers.extra] skip identity resolution
 * and use Deezer's direct track lookup — typically 10x faster than searching by title/artist.
 */
suspend fun EnrichmentEngine.resolveTrackPreviews(
    tracks: List<TrackPreviewRequest>,
    forceRefresh: Boolean = false,
): List<TrackPreviewResult> = coroutineScope {
    tracks.map { track ->
        async {
            val results = enrich(
                EnrichmentRequest.forTrack(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    identifiers = track.identifiers,
                ),
                types = setOf(EnrichmentType.TRACK_PREVIEW),
                forceRefresh = forceRefresh,
            )
            TrackPreviewResult(
                title = track.title,
                artist = track.artist,
                preview = results.get<EnrichmentData.TrackPreview>(EnrichmentType.TRACK_PREVIEW),
            )
        }
    }.awaitAll()
}
