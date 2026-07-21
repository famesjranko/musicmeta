package com.landofoz.musicmeta.provider.lastfm

internal data class LastFmAlbumInfo(
    val name: String,
    val artist: String,
    val playcount: Long?,
    val listeners: Long?,
    val tags: List<String>,
    val wiki: String?,
    val trackCount: Int?,
)

internal data class LastFmArtistInfo(
    val name: String,
    val bio: String?,
    val tags: List<String>,
    val listeners: Long?,
    val playcount: Long?,
)

internal data class LastFmSimilarArtist(
    val name: String,
    val matchScore: Float,
    val mbid: String?,
)

internal data class LastFmSimilarTrack(
    val title: String,
    val artist: String,
    val matchScore: Float,
    val mbid: String?,
)

internal data class LastFmTopTrack(
    val title: String,
    val artist: String,
    val playcount: Long?,
    val listeners: Long?,
    val mbid: String?,
    val rank: Int,
)

internal data class LastFmTrackInfo(
    val title: String,
    val artist: String,
    val playcount: Long?,
    val listeners: Long?,
    val mbid: String?,
)
