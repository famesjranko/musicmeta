package com.landofoz.musicmeta

import kotlinx.serialization.Serializable

/**
 * Describes the music entity to enrich and what identifiers are available.
 * Identifiers are progressively filled during enrichment (e.g., MusicBrainz
 * resolves MBIDs that downstream providers then use).
 */
sealed class EnrichmentRequest {
    abstract val identifiers: EnrichmentIdentifiers

    /** Returns a copy with updated identifiers. */
    abstract fun withIdentifiers(identifiers: EnrichmentIdentifiers): EnrichmentRequest

    data class ForAlbum(
        override val identifiers: EnrichmentIdentifiers,
        val title: String,
        val artist: String,
        val trackCount: Int? = null,
        val year: Int? = null,
    ) : EnrichmentRequest() {
        override fun withIdentifiers(identifiers: EnrichmentIdentifiers) = copy(identifiers = identifiers)
    }

    data class ForArtist(
        override val identifiers: EnrichmentIdentifiers,
        val name: String,
    ) : EnrichmentRequest() {
        override fun withIdentifiers(identifiers: EnrichmentIdentifiers) = copy(identifiers = identifiers)
    }

    data class ForTrack(
        override val identifiers: EnrichmentIdentifiers,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
    ) : EnrichmentRequest() {
        override fun withIdentifiers(identifiers: EnrichmentIdentifiers) = copy(identifiers = identifiers)
    }

    companion object {
        fun forAlbum(title: String, artist: String, mbid: String? = null) =
            ForAlbum(EnrichmentIdentifiers(musicBrainzId = mbid), title, artist)

        fun forArtist(name: String, mbid: String? = null) =
            ForArtist(EnrichmentIdentifiers(musicBrainzId = mbid), name)

        fun forTrack(
            title: String,
            artist: String,
            album: String? = null,
            durationMs: Long? = null,
            mbid: String? = null,
        ) = ForTrack(EnrichmentIdentifiers(musicBrainzId = mbid), title, artist, album, durationMs)

        /** Types meaningful for [ForArtist] requests. */
        val DEFAULT_ARTIST_TYPES: Set<EnrichmentType> = setOf(
            EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO,
            EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND,
            EnrichmentType.ARTIST_LOGO, EnrichmentType.ARTIST_BANNER,
            EnrichmentType.ARTIST_POPULARITY, EnrichmentType.SIMILAR_ARTISTS,
            EnrichmentType.BAND_MEMBERS, EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentType.ARTIST_LINKS, EnrichmentType.ARTIST_TIMELINE,
            EnrichmentType.ARTIST_RADIO, EnrichmentType.ARTIST_TOP_TRACKS,
            EnrichmentType.GENRE_DISCOVERY,
        )

        /** Types meaningful for [ForAlbum] requests. */
        val DEFAULT_ALBUM_TYPES: Set<EnrichmentType> = setOf(
            EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_ART_BACK,
            EnrichmentType.ALBUM_BOOKLET, EnrichmentType.CD_ART,
            EnrichmentType.GENRE, EnrichmentType.LABEL,
            EnrichmentType.RELEASE_DATE, EnrichmentType.RELEASE_TYPE,
            EnrichmentType.COUNTRY, EnrichmentType.ALBUM_METADATA,
            EnrichmentType.ALBUM_TRACKS, EnrichmentType.RELEASE_EDITIONS,
            EnrichmentType.SIMILAR_ALBUMS, EnrichmentType.GENRE_DISCOVERY,
        )

        /** Types meaningful for [ForTrack] requests. */
        val DEFAULT_TRACK_TYPES: Set<EnrichmentType> = setOf(
            EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED,
            EnrichmentType.LYRICS_PLAIN, EnrichmentType.TRACK_POPULARITY,
            EnrichmentType.SIMILAR_TRACKS, EnrichmentType.CREDITS,
            EnrichmentType.ALBUM_ART, EnrichmentType.GENRE_DISCOVERY,
        )

        /** Returns the default type set for a given request kind. */
        fun defaultTypesFor(request: EnrichmentRequest): Set<EnrichmentType> = when (request) {
            is ForArtist -> DEFAULT_ARTIST_TYPES
            is ForAlbum -> DEFAULT_ALBUM_TYPES
            is ForTrack -> DEFAULT_TRACK_TYPES
        }
    }
}

/**
 * Known identifiers for a music entity, progressively filled during enrichment.
 * MusicBrainz identity resolution populates most of these.
 */
@Serializable
data class EnrichmentIdentifiers(
    val musicBrainzId: String? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val wikidataId: String? = null,
    val isrc: String? = null,
    val barcode: String? = null,
    val wikipediaTitle: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    /** Retrieves an extra identifier by key, or null if not present. */
    fun get(key: String): String? = extra[key]

    /** Returns a copy with the given extra identifier added (immutable). */
    fun withExtra(key: String, value: String): EnrichmentIdentifiers =
        copy(extra = extra + (key to value))
}
