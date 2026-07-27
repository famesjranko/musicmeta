package com.landofoz.musicmeta

import kotlinx.serialization.Serializable

/** Typed payload returned by a successful enrichment. */
@Serializable
sealed class EnrichmentData {

    @Serializable
    data class Artwork(
        val url: String,
        val width: Int? = null,
        val height: Int? = null,
        val thumbnailUrl: String? = null,
        val sizes: List<ArtworkSize>? = null,
        /** Images from other providers, available when artwork is merged from multiple sources. */
        val alternatives: List<ArtworkSource>? = null,
    ) : EnrichmentData()

    @Serializable
    data class Metadata(
        val genres: List<String>? = null,
        val genreTags: List<GenreTag>? = null,
        val label: String? = null,
        val releaseDate: String? = null,
        val releaseType: String? = null,
        val country: String? = null,
        val barcode: String? = null,
        val disambiguation: String? = null,
        val artistType: String? = null,
        val beginDate: String? = null,
        val endDate: String? = null,
        val isrc: String? = null,
        val trackCount: Int? = null,
        val explicit: Boolean? = null,
        val catalogNumber: String? = null,
        val communityRating: Float? = null,
    ) : EnrichmentData()

    @Serializable
    data class Lyrics(
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
    ) : EnrichmentData()

    @Serializable
    data class Biography(
        val text: String,
        val source: String,
        val language: String = "en",
        val thumbnailUrl: String? = null,
    ) : EnrichmentData()

    @Serializable
    data class SimilarArtists(
        val artists: List<SimilarArtist>,
    ) : EnrichmentData()

    @Serializable
    data class Popularity(
        val listenCount: Long? = null,
        val listenerCount: Long? = null,
        val rank: Int? = null,
        val topTracks: List<PopularTrack>? = null,
    ) : EnrichmentData()

    @Serializable
    data class BandMembers(val members: List<BandMember>) : EnrichmentData()

    @Serializable
    data class Discography(val albums: List<DiscographyAlbum>) : EnrichmentData()

    @Serializable
    data class Tracklist(val tracks: List<TrackInfo>) : EnrichmentData()

    @Serializable
    data class SimilarTracks(val tracks: List<SimilarTrack>) : EnrichmentData()

    @Serializable
    data class ArtistLinks(val links: List<ExternalLink>) : EnrichmentData()

    @Serializable
    data class Credits(val credits: List<Credit>) : EnrichmentData()

    @Serializable
    data class ReleaseEditions(val editions: List<ReleaseEdition>) : EnrichmentData()

    @Serializable
    data class ArtistTimeline(val events: List<TimelineEvent>) : EnrichmentData()

    @Serializable
    data class RadioPlaylist(val tracks: List<RadioTrack>) : EnrichmentData()

    @Serializable
    data class SimilarAlbums(val albums: List<SimilarAlbum>) : EnrichmentData()

    @Serializable
    data class GenreDiscovery(val relatedGenres: List<GenreAffinity>) : EnrichmentData()

    @Serializable
    data class TopTracks(val tracks: List<TopTrack>) : EnrichmentData()

    @Serializable
    data class TrackPreview(
        val url: String,
        val durationMs: Long = 30000,
        val source: String,
    ) : EnrichmentData()
}

@Serializable
data class ArtworkSize(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val label: String? = null,
)

/** An image from an alternative provider, used when artwork is merged from multiple sources. */
@Serializable
data class ArtworkSource(
    val provider: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val sizes: List<ArtworkSize>? = null,
)

@Serializable
data class SimilarArtist(
    val name: String,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val matchScore: Float,
    val sources: List<String> = emptyList(),
)

@Serializable
data class PopularTrack(
    val title: String,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val listenCount: Long,
    val rank: Int,
)

@Serializable
data class BandMember(
    val name: String,
    val role: String? = null,
    val activePeriod: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class DiscographyAlbum(
    val title: String,
    val year: String? = null,
    val type: String? = null,
    val thumbnailUrl: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class TrackInfo(
    val title: String,
    val position: Int,
    val durationMs: Long? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class SimilarTrack(
    val title: String,
    val artist: String,
    val matchScore: Float,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val sources: List<String> = emptyList(),
)

@Serializable
data class ExternalLink(
    val type: String,
    val url: String,
    val label: String? = null,
)

@Serializable
data class Credit(
    val name: String,
    val role: String,
    val roleCategory: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class ReleaseEdition(
    val title: String,
    val format: String? = null,
    val country: String? = null,
    val year: Int? = null,
    val label: String? = null,
    val catalogNumber: String? = null,
    val barcode: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class TimelineEvent(
    val date: String,
    val type: String,
    val description: String,
    val relatedEntity: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class GenreTag(
    val name: String,
    val confidence: Float,
    val sources: List<String> = emptyList(),
)

@Serializable
data class RadioTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

/**
 * An album recommended alongside a seed album.
 *
 * The only shipped `SIMILAR_ALBUMS` provider derives these from artists similar to the seed
 * *artist* — Deezer exposes no album-level similarity — which is why the score is named
 * [artistMatchScore]. Two albums by one artist therefore yield near-identical lists. See
 * `SimilarAlbumsProvider`'s KDoc.
 *
 * @property artistMatchScore the source artist's similarity rank (1.0 for the closest, falling
 *   with rank) multiplied by an era-proximity factor of 1.2, 1.0 or 0.8. **Not normalised and not
 *   clamped**: the product can exceed 1.0, up to 1.2, and never reaches 0. Rank within one
 *   result list; do not read it as a probability or compare it across providers.
 */
@Serializable
data class SimilarAlbum(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val artistMatchScore: Float,
    val thumbnailUrl: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class GenreAffinity(
    val name: String,
    val affinity: Float,
    val relationship: String,
    val sourceGenres: List<String>,
)

@Serializable
data class TopTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val listenCount: Long? = null,
    val listenerCount: Long? = null,
    val rank: Int,
    val sources: List<String> = emptyList(),
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)
