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
    val nbTracks: Int? = null,
    val recordType: String? = null,
    val explicitLyrics: Boolean? = null,
)

/** Artist search result from Deezer API. */
data class DeezerArtistSearchResult(
    val id: Long,
    val name: String,
)

/** Album entry from Deezer artist albums endpoint. */
data class DeezerArtistAlbum(
    val id: Long,
    val title: String,
    val releaseDate: String?,
    val recordType: String?,
    val coverSmall: String?,
    val coverMedium: String?,
)

/** Track entry from Deezer album tracks endpoint. */
data class DeezerTrack(
    val id: Long,
    val title: String,
    val trackPosition: Int,
    val durationSec: Int,
)

/** Related artist entry from Deezer /artist/{id}/related endpoint. */
data class DeezerRelatedArtist(
    val id: Long,
    val name: String,
)
