package com.landofoz.musicmeta.provider.listenbrainz

/** A popular track recording from the ListenBrainz API. */
data class ListenBrainzPopularTrack(
    val recordingMbid: String,
    val title: String,
    val artistName: String,
    val listenCount: Long,
)

/** Batch recording popularity from POST /1/popularity/recording. */
data class ListenBrainzRecordingPopularity(
    val recordingMbid: String,
    val totalListenCount: Long,
    val totalUserCount: Long,
)

/** Batch artist popularity from POST /1/popularity/artist. */
data class ListenBrainzArtistPopularity(
    val artistMbid: String,
    val totalListenCount: Long,
    val totalUserCount: Long,
)

/** Top release group from GET /1/popularity/top-release-groups-for-artist/{mbid}. */
data class ListenBrainzTopReleaseGroup(
    val releaseGroupMbid: String,
    val releaseGroupName: String,
    val artistName: String,
    val listenCount: Long,
)
