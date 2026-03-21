package com.landofoz.musicmeta.provider.itunes

/** Album search result from the iTunes Search API. */
data class ITunesAlbumResult(
    val collectionId: Long = 0,
    val collectionName: String,
    val artistName: String,
    val artworkUrl: String?,
    val releaseDate: String? = null,
    val primaryGenreName: String? = null,
    val country: String? = null,
    val trackCount: Int? = null,
)

/** Track result from the iTunes Lookup API (`/lookup?id={collectionId}&entity=song`). */
data class ITunesTrackResult(
    val trackId: Long = 0,
    val trackName: String,
    val trackNumber: Int,
    val trackTimeMillis: Long? = null,
    val artistName: String,
    val collectionName: String? = null,
)
