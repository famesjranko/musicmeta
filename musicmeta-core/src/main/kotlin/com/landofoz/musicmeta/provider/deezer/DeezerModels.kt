package com.landofoz.musicmeta.provider.deezer

/** Album search result from the Deezer API. */
data class DeezerAlbumResult(
    val id: Long = 0,
    val title: String,
    val artistName: String,
    val coverSmall: String?,
    val coverMedium: String?,
    val coverBig: String?,
    val coverXl: String?,
)
