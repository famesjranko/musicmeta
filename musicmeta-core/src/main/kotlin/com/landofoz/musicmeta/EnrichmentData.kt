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
    ) : EnrichmentData()

    @Serializable
    data class Metadata(
        val genres: List<String>? = null,
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

}

@Serializable
data class ArtworkSize(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val label: String? = null,
)

@Serializable
data class SimilarArtist(
    val name: String,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val matchScore: Float,
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
)

@Serializable
data class ExternalLink(
    val type: String,
    val url: String,
    val label: String? = null,
)
