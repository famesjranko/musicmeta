package com.landofoz.musicmeta

/**
 * Structured view of enrichment results for an album.
 *
 * All fields are computed properties that delegate to [results].
 */
data class AlbumProfile(val title: String, val artist: String, val results: EnrichmentResults) {

    // --- Identity ---
    val identifiers: EnrichmentIdentifiers get() = results.identity.identifiers
    val canonicalStatus: CanonicalStatus get() = results.identity.status
    val identityMatchScore: Int? get() = results.identity.matchScore
    val suggestions: List<SearchCandidate> get() = results.identity.suggestions

    // --- Artwork ---
    val artwork: EnrichmentData.Artwork? get() = results.albumArt()
    val artworkBack: EnrichmentData.Artwork? get() = results.get(EnrichmentType.ALBUM_ART_BACK)
    val booklet: EnrichmentData.Artwork? get() = results.get(EnrichmentType.ALBUM_BOOKLET)
    val cdArt: EnrichmentData.Artwork? get() = results.get(EnrichmentType.CD_ART)

    // --- Text ---
    val description: EnrichmentData.Biography? get() = results.albumDescription()

    // --- Metadata ---
    val genres: List<GenreTag> get() = results.genreTags()
    val label: String? get() = results.label()
    val releaseDate: String? get() = results.releaseDate()
    val releaseType: String? get() = results.releaseType()
    val country: String? get() = results.country()

    // --- Tracklist & Editions ---
    val tracks: List<TrackInfo> get() =
        results.get<EnrichmentData.Tracklist>(EnrichmentType.ALBUM_TRACKS)?.tracks.orEmpty()
    val editions: List<ReleaseEdition> get() =
        results.get<EnrichmentData.ReleaseEditions>(EnrichmentType.RELEASE_EDITIONS)?.editions.orEmpty()

    // --- Recommendations ---
    val similarAlbums: EnrichmentData.SimilarAlbums? get() = results.similarAlbums()
    val genreDiscovery: List<GenreAffinity> get() =
        results.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)?.relatedGenres.orEmpty()
}
