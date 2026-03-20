package com.cascade.enrichment.provider.lastfm

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
