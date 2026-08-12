package com.landofoz.musicmeta.provider.deezer

/** Album search result from the Deezer API. */
internal data class DeezerAlbumResult(
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

/** Album resource from `GET /album/{id}` — carries fields the search hit does not. */
internal data class DeezerAlbum(
    val id: Long,
    val upc: String? = null,
    val label: String? = null,
    val releaseDate: String? = null,
)

/** Artist search result from Deezer API. */
internal data class DeezerArtistSearchResult(
    val id: Long,
    val name: String,
    val pictureSmall: String? = null,
    val pictureMedium: String? = null,
    val pictureBig: String? = null,
    val pictureXl: String? = null,
)

/** Album entry from Deezer artist albums endpoint. */
internal data class DeezerArtistAlbum(
    val id: Long,
    val title: String,
    val releaseDate: String?,
    val recordType: String?,
    val coverSmall: String?,
    val coverMedium: String?,
)

/** Track entry from Deezer album tracks endpoint. */
internal data class DeezerTrack(
    val id: Long,
    val title: String,
    val trackPosition: Int,
    val durationSec: Int,
)

/** Related artist entry from Deezer /artist/{id}/related endpoint. */
internal data class DeezerRelatedArtist(
    val id: Long,
    val name: String,
)

/** Track search result from Deezer API. */
internal data class DeezerTrackSearchResult(
    val id: Long,
    val title: String,
    val artistName: String,
    val previewUrl: String? = null,
    val durationSec: Int? = null,
    val albumTitle: String? = null,
    /** The track's artist's Deezer id, straight off the search payload — null when absent/0. */
    val artistId: Long? = null,
)

/** Top track entry from Deezer /artist/{id}/top endpoint. */
internal data class DeezerTopTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val durationSec: Int = 0,
    val rank: Int = 0,
)

/** Radio track entry from Deezer /artist/{id}/radio endpoint. */
internal data class DeezerRadioTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val durationSec: Int = 0,
)
