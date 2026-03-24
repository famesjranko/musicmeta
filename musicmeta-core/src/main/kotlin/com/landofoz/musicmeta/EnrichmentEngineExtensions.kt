package com.landofoz.musicmeta

/**
 * Convenience extension: enriches an artist and returns a structured [ArtistProfile].
 *
 * @param types Override the default type set to request fewer (or more) types.
 * @param forceRefresh When true, bypasses the cache and fetches fresh data from providers.
 */
suspend fun EnrichmentEngine.artistProfile(
    name: String,
    mbid: String? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ARTIST_TYPES,
    forceRefresh: Boolean = false,
): ArtistProfile {
    val results = enrich(EnrichmentRequest.forArtist(name, mbid), types, forceRefresh)
    return ArtistProfile(name, results)
}

/** Re-enrich from a [SearchCandidate] (e.g., after a "did you mean?" pick). */
suspend fun EnrichmentEngine.artistProfile(
    candidate: SearchCandidate,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ARTIST_TYPES,
    forceRefresh: Boolean = false,
): ArtistProfile = artistProfile(candidate.title, candidate.identifiers.musicBrainzId, types, forceRefresh)

/**
 * Convenience extension: enriches an album and returns a structured [AlbumProfile].
 */
suspend fun EnrichmentEngine.albumProfile(
    title: String,
    artist: String,
    mbid: String? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_ALBUM_TYPES,
    forceRefresh: Boolean = false,
): AlbumProfile {
    val results = enrich(EnrichmentRequest.forAlbum(title, artist, mbid), types, forceRefresh)
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
    types,
    forceRefresh,
)

/**
 * Convenience extension: enriches a track and returns a structured [TrackProfile].
 */
suspend fun EnrichmentEngine.trackProfile(
    title: String,
    artist: String,
    album: String? = null,
    mbid: String? = null,
    types: Set<EnrichmentType> = EnrichmentRequest.DEFAULT_TRACK_TYPES,
    forceRefresh: Boolean = false,
): TrackProfile {
    val results = enrich(EnrichmentRequest.forTrack(title, artist, album, mbid = mbid), types, forceRefresh)
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
    types,
    forceRefresh,
)
