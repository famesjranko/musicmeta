package com.landofoz.musicmeta

/** Types of data that can be enriched for a music entity. */
enum class EnrichmentType {
    // Artwork
    ALBUM_ART,
    ARTIST_PHOTO,
    ARTIST_BACKGROUND,
    ARTIST_LOGO,
    CD_ART,

    // Structured metadata
    GENRE,
    LABEL,
    RELEASE_DATE,
    RELEASE_TYPE,
    COUNTRY,
    SIMILAR_ARTISTS,

    // Text content
    ARTIST_BIO,
    LYRICS_SYNCED,
    LYRICS_PLAIN,

    // Statistics
    TRACK_POPULARITY,
    ARTIST_POPULARITY,
}
