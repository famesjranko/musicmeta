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
)
