package com.landofoz.musicmeta

/**
 * Types of data that can be enriched for a music entity.
 *
 * @param defaultTtlMs Default cache time-to-live in milliseconds for this type.
 *   Can be overridden per-type via [EnrichmentConfig.ttlOverrides].
 */
enum class EnrichmentType(val defaultTtlMs: Long) {
    // Artwork — 90 days
    ALBUM_ART(90L * 24 * 60 * 60 * 1000),
    ARTIST_PHOTO(30L * 24 * 60 * 60 * 1000),
    ARTIST_BACKGROUND(30L * 24 * 60 * 60 * 1000),
    ARTIST_LOGO(90L * 24 * 60 * 60 * 1000),
    CD_ART(90L * 24 * 60 * 60 * 1000),

    // Structured metadata
    GENRE(90L * 24 * 60 * 60 * 1000),
    LABEL(365L * 24 * 60 * 60 * 1000),
    RELEASE_DATE(365L * 24 * 60 * 60 * 1000),
    RELEASE_TYPE(365L * 24 * 60 * 60 * 1000),
    COUNTRY(365L * 24 * 60 * 60 * 1000),
    ALBUM_METADATA(90L * 24 * 60 * 60 * 1000),
    SIMILAR_ARTISTS(30L * 24 * 60 * 60 * 1000),

    // Text content
    ARTIST_BIO(30L * 24 * 60 * 60 * 1000),
    LYRICS_SYNCED(90L * 24 * 60 * 60 * 1000),
    LYRICS_PLAIN(90L * 24 * 60 * 60 * 1000),

    // Statistics — 7 days
    TRACK_POPULARITY(7L * 24 * 60 * 60 * 1000),
    ARTIST_POPULARITY(7L * 24 * 60 * 60 * 1000),

    // Relationships
    BAND_MEMBERS(30L * 24 * 60 * 60 * 1000),
    SIMILAR_TRACKS(30L * 24 * 60 * 60 * 1000),
    ARTIST_LINKS(90L * 24 * 60 * 60 * 1000),
    CREDITS(30L * 24 * 60 * 60 * 1000),

    // Additional metadata
    ARTIST_DISCOGRAPHY(30L * 24 * 60 * 60 * 1000),
    ALBUM_TRACKS(365L * 24 * 60 * 60 * 1000),
    RELEASE_EDITIONS(365L * 24 * 60 * 60 * 1000),

    // Additional artwork
    ARTIST_BANNER(90L * 24 * 60 * 60 * 1000),
    ALBUM_ART_BACK(90L * 24 * 60 * 60 * 1000),
    ALBUM_BOOKLET(90L * 24 * 60 * 60 * 1000),

    // Composite
    ARTIST_TIMELINE(30L * 24 * 60 * 60 * 1000),

    // Radio / recommendations — 7 days
    ARTIST_RADIO(7L * 24 * 60 * 60 * 1000),
    // Radio discovery (LB Radio) — 7 days
    ARTIST_RADIO_DISCOVERY(7L * 24 * 60 * 60 * 1000),

    // Top tracks — 7 days
    ARTIST_TOP_TRACKS(7L * 24 * 60 * 60 * 1000),

    // Discovery / recommendations — 30 days
    SIMILAR_ALBUMS(30L * 24 * 60 * 60 * 1000),
    GENRE_DISCOVERY(30L * 24 * 60 * 60 * 1000),
}
