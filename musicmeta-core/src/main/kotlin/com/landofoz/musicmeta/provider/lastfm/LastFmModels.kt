package com.landofoz.musicmeta.provider.lastfm

data class LastFmArtistInfo(
    val name: String,
    val bio: String?,
    val tags: List<String>,
    val listeners: Long?,
    val playcount: Long?,
)

data class LastFmSimilarArtist(
    val name: String,
    val matchScore: Float,
    val mbid: String?,
)

data class LastFmSimilarTrack(
    val title: String,
    val artist: String,
    val matchScore: Float,
    val mbid: String?,
)

data class LastFmTrackInfo(
    val title: String,
    val artist: String,
    val playcount: Long?,
    val listeners: Long?,
    val mbid: String?,
)
