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
    val pictureSmall: String? = null,
    val pictureMedium: String? = null,
    val pictureBig: String? = null,
    val pictureXl: String? = null,
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

/** Track search result from Deezer API. */
data class DeezerTrackSearchResult(
    val id: Long,
    val title: String,
    val artistName: String,
)

/** Top track entry from Deezer /artist/{id}/top endpoint. */
data class DeezerTopTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val durationSec: Int = 0,
    val rank: Int = 0,
)

/** Radio track entry from Deezer /artist/{id}/radio or /track/{id}/radio endpoint. */
data class DeezerRadioTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val durationSec: Int = 0,
)
