package com.landofoz.musicmeta

/**
 * Structured view of enrichment results for a track.
 *
 * All fields are computed properties that delegate to [results].
 */
data class TrackProfile(val title: String, val artist: String, val results: EnrichmentResults) {

    // --- Identity ---
    val identifiers: EnrichmentIdentifiers get() = results.identity?.identifiers ?: EnrichmentIdentifiers()
    val identityMatch: IdentityMatch? get() = results.identity?.match
    val identityMatchScore: Int? get() = results.identity?.matchScore
    val suggestions: List<SearchCandidate> get() = results.identity?.suggestions ?: emptyList()

    // --- Metadata ---
    val genres: List<GenreTag> get() = results.genreTags()

    // --- Content ---
    val lyrics: EnrichmentData.Lyrics? get() = results.lyrics()
    val credits: EnrichmentData.Credits? get() = results.credits()
    val artwork: EnrichmentData.Artwork? get() = results.albumArt()

    // --- Stats & Recommendations ---
    val popularity: EnrichmentData.Popularity? get() = results.trackPopularity()
    val similarTracks: EnrichmentData.SimilarTracks? get() = results.similarTracks()
    val preview: EnrichmentData.TrackPreview? get() =
        results.get<EnrichmentData.TrackPreview>(EnrichmentType.TRACK_PREVIEW)
    val genreDiscovery: List<GenreAffinity> get() =
        results.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)?.relatedGenres ?: emptyList()
}
