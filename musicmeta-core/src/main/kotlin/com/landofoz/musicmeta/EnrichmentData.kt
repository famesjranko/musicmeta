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

    /**
     * Resolved identifiers + metadata from an identity lookup (e.g., MusicBrainz).
     * Used by the engine to enrich the request before fanning out, and also
     * carries metadata that came as a side effect of the identity search.
     */
    @Serializable
    data class IdentifierResolution(
        val musicBrainzId: String? = null,
        val musicBrainzReleaseGroupId: String? = null,
        val wikidataId: String? = null,
        val wikipediaTitle: String? = null,
        val score: Int = 0,
        val metadata: Metadata? = null,
        val hasFrontCover: Boolean = false,
    ) : EnrichmentData()
}

@Serializable
data class SimilarArtist(
    val name: String,
    val musicBrainzId: String? = null,
    val matchScore: Float,
)

@Serializable
data class PopularTrack(
    val title: String,
    val musicBrainzId: String? = null,
    val listenCount: Long,
    val rank: Int,
)
