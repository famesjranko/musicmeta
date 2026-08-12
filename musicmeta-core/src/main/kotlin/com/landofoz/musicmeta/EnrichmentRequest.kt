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
        fun forAlbum(
            title: String,
            artist: String,
            mbid: String? = null,
            identifiers: EnrichmentIdentifiers? = null,
        ) = ForAlbum(
            identifiers = (identifiers ?: EnrichmentIdentifiers()).let {
                if (mbid != null) it.copy(musicBrainzId = mbid) else it
            },
            title = title,
            artist = artist,
        )

        fun forArtist(
            name: String,
            mbid: String? = null,
            identifiers: EnrichmentIdentifiers? = null,
        ) = ForArtist(
            identifiers = (identifiers ?: EnrichmentIdentifiers()).let {
                if (mbid != null) it.copy(musicBrainzId = mbid) else it
            },
            name = name,
        )

        fun forTrack(
            title: String,
            artist: String,
            album: String? = null,
            durationMs: Long? = null,
            mbid: String? = null,
            identifiers: EnrichmentIdentifiers? = null,
        ) = ForTrack(
            identifiers = (identifiers ?: EnrichmentIdentifiers()).let {
                if (mbid != null) it.copy(musicBrainzId = mbid) else it
            },
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )

        /**
         * An album request naming nothing but [mbid], for a caller holding a release id and no
         * title.
         *
         * Identity resolution fills the title and artist from the release MusicBrainz holds under
         * [mbid], so the name-search providers (Deezer, LRCLIB, iTunes, Last.fm, Discogs) work
         * normally — none of them can use an MBID. Two consequences a caller has to weigh:
         *
         * - **An id MusicBrainz holds nothing under leaves the request with no name at all**, and
         *   the answer is an honest `NotFound` rather than a search for something. Third-party
         *   identifiers go stale in bulk — 1212 of 1710 Last.fm recording MBIDs were held under no
         *   entity type when measured 2026-08-12 — so pass [forAlbum] with the names whenever you
         *   have them, and keep this for the case where you genuinely do not.
         * - The result is cached under MusicBrainz's **canonical** name, not one you supplied,
         *   because there is none to alias to.
         */
        fun forAlbumByMbid(
            mbid: String,
            identifiers: EnrichmentIdentifiers? = null,
        ) = ForAlbum(
            identifiers = (identifiers ?: EnrichmentIdentifiers()).copy(musicBrainzId = mbid),
            title = "",
            artist = "",
        )

        /** An artist request naming nothing but [mbid]; see [forAlbumByMbid] for what that costs. */
        fun forArtistByMbid(
            mbid: String,
            identifiers: EnrichmentIdentifiers? = null,
        ) = ForArtist(
            identifiers = (identifiers ?: EnrichmentIdentifiers()).copy(musicBrainzId = mbid),
            name = "",
        )

        /** A track request naming nothing but [mbid]; see [forAlbumByMbid] for what that costs. */
        fun forTrackByMbid(
            mbid: String,
            identifiers: EnrichmentIdentifiers? = null,
        ) = ForTrack(
            identifiers = (identifiers ?: EnrichmentIdentifiers()).copy(musicBrainzId = mbid),
            title = "",
            artist = "",
        )

        /** Types meaningful for [ForArtist] requests. */
        val DEFAULT_ARTIST_TYPES: Set<EnrichmentType> = setOf(
            EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO,
            EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND,
            EnrichmentType.ARTIST_LOGO, EnrichmentType.ARTIST_BANNER,
            EnrichmentType.ARTIST_POPULARITY, EnrichmentType.SIMILAR_ARTISTS,
            EnrichmentType.BAND_MEMBERS, EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentType.ARTIST_LINKS, EnrichmentType.ARTIST_TIMELINE,
            EnrichmentType.ARTIST_RADIO, EnrichmentType.ARTIST_RADIO_DISCOVERY,
            EnrichmentType.ARTIST_TOP_TRACKS, EnrichmentType.GENRE_DISCOVERY,
        )

        /**
         * Types meaningful for [ForAlbum] requests.
         *
         * [EnrichmentType.ALBUM_DESCRIPTION] is included: its top source is keyless Wikipedia and
         * the type is long-cached (30 days), the same precedent that put [EnrichmentType.SIMILAR_ALBUMS]
         * and [EnrichmentType.GENRE_DISCOVERY] here. A consumer who doesn't want the extra fetch
         * passes an explicit type set instead of this default.
         */
        val DEFAULT_ALBUM_TYPES: Set<EnrichmentType> = setOf(
            EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_ART_BACK,
            EnrichmentType.ALBUM_BOOKLET, EnrichmentType.CD_ART,
            EnrichmentType.GENRE, EnrichmentType.LABEL,
            EnrichmentType.RELEASE_DATE, EnrichmentType.RELEASE_TYPE,
            EnrichmentType.COUNTRY, EnrichmentType.ALBUM_METADATA,
            EnrichmentType.ALBUM_TRACKS, EnrichmentType.RELEASE_EDITIONS,
            EnrichmentType.SIMILAR_ALBUMS, EnrichmentType.GENRE_DISCOVERY,
            EnrichmentType.ALBUM_DESCRIPTION,
        )

        /** Types meaningful for [ForTrack] requests. */
        val DEFAULT_TRACK_TYPES: Set<EnrichmentType> = setOf(
            EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED,
            EnrichmentType.LYRICS_PLAIN, EnrichmentType.TRACK_POPULARITY,
            EnrichmentType.SIMILAR_TRACKS, EnrichmentType.CREDITS,
            EnrichmentType.ALBUM_ART, EnrichmentType.GENRE_DISCOVERY,
            EnrichmentType.TRACK_METADATA,
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
 * Namespaces a `String`-keyed external id. Members may only be appended; keep a consumer
 * `when` over this non-exhaustive.
 */
enum class IdentifierNamespace(internal val key: String) {
    MUSICBRAINZ_ARTIST("artistMbid"),
    MUSICBRAINZ_RELEASE("releaseMbid"),
    MUSICBRAINZ_RECORDING("recordingMbid"),
    DISCOGS_ARTIST("discogsArtistId"),
    SPOTIFY_ARTIST("spotifyArtistId"),
    ITUNES_ARTIST("itunesArtistId"),

    /** The key's meaning depends on the request: an artist, album or track id. */
    DEEZER("deezerId"),
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

    /** Retrieves an extra identifier by namespace, or null if not present. */
    fun get(ns: IdentifierNamespace): String? = extra[ns.key]

    /** Returns a copy with the given namespaced identifier added (immutable). */
    fun with(ns: IdentifierNamespace, value: String): EnrichmentIdentifiers =
        copy(extra = extra + (ns.key to value))
}
