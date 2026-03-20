package com.landofoz.musicmeta.provider.listenbrainz

/** A popular track recording from the ListenBrainz API. */
data class ListenBrainzPopularTrack(
    val recordingMbid: String,
    val title: String,
    val artistName: String,
    val listenCount: Long,
)
