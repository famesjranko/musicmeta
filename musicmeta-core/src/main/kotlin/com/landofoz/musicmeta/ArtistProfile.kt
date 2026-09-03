package com.landofoz.musicmeta

/**
 * Structured view of enrichment results for an artist.
 *
 * All fields are computed properties that delegate to [results], so they
 * always reflect the underlying data without duplication. Use [results]
 * directly for error diagnostics (e.g., checking if a type was [EnrichmentResult.Error] and what
 * its [ErrorKind] was).
 */
data class ArtistProfile(val name: String, val results: EnrichmentResults) {

    // --- Identity ---
    val identifiers: EnrichmentIdentifiers get() = results.identity.identifiers
    val canonicalStatus: CanonicalStatus get() = results.identity.status
    val identityMatchScore: Int? get() = results.identity.matchScore
    val suggestions: List<SearchCandidate> get() = results.identity.suggestions

    // --- Artwork ---
    val photo: EnrichmentData.Artwork? get() = results.artistPhoto()
    val background: EnrichmentData.Artwork? get() = results.get(EnrichmentType.ARTIST_BACKGROUND)
    val logo: EnrichmentData.Artwork? get() = results.get(EnrichmentType.ARTIST_LOGO)
    val banner: EnrichmentData.Artwork? get() = results.get(EnrichmentType.ARTIST_BANNER)

    // --- Text & Metadata ---
    val bio: EnrichmentData.Biography? get() = results.biography()
    val genres: List<GenreTag> get() = results.genreTags()

    /**
     * ISO 3166-1 alpha-2 where the upstream supplies a country (`GB`). Album metadata from Discogs
     * may carry a region label instead (`Europe`), and a multi-country MusicBrainz release may
     * carry MusicBrainz's `XE`/`XW`; null when no country-level area exists.
     */
    val country: String? get() = results.country()

    // --- Members & Relationships ---
    val members: List<BandMember> get() =
        results.get<EnrichmentData.BandMembers>(EnrichmentType.BAND_MEMBERS)?.members.orEmpty()
    val links: List<ExternalLink> get() =
        results.get<EnrichmentData.ArtistLinks>(EnrichmentType.ARTIST_LINKS)?.links.orEmpty()
    val discography: List<DiscographyAlbum> get() =
        results.discography()?.albums.orEmpty()

    // --- Stats & Recommendations ---
    val popularity: EnrichmentData.Popularity? get() = results.artistPopularity()
    val topTracks: EnrichmentData.TopTracks? get() = results.topTracks()
    val similarArtists: EnrichmentData.SimilarArtists? get() = results.similarArtists()
    val radio: EnrichmentData.RadioPlaylist? get() = results.radio()
    val radioDiscovery: EnrichmentData.RadioPlaylist? get() =
        results.get<EnrichmentData.RadioPlaylist>(EnrichmentType.ARTIST_RADIO_DISCOVERY)
    val similarAlbums: EnrichmentData.SimilarAlbums? get() = results.similarAlbums()
    val timeline: List<TimelineEvent> get() =
        results.get<EnrichmentData.ArtistTimeline>(EnrichmentType.ARTIST_TIMELINE)?.events.orEmpty()
    val genreDiscovery: List<GenreAffinity> get() =
        results.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)?.relatedGenres.orEmpty()
}
