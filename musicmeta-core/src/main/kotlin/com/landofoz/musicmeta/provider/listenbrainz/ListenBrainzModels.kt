package com.landofoz.musicmeta.provider.listenbrainz

/** A popular track recording from the ListenBrainz API. */
internal data class ListenBrainzPopularTrack(
    val recordingMbid: String,
    val title: String,
    val artistName: String,
    val listenCount: Long,
    val listenerCount: Long? = null,
    val durationMs: Long? = null,
    val albumName: String? = null,
)

/**
 * Batch recording popularity from POST /1/popularity/recording.
 *
 * LB returns an entry with a JSON-null `total_listen_count` when it has no data for the
 * recording; the parser drops those entries entirely, so an instance of this class always
 * carries a genuine (possibly zero) [totalListenCount]. [totalUserCount] can still be null:
 * LB has been observed sending a real listen count alongside a null user count.
 */
internal data class ListenBrainzRecordingPopularity(
    val recordingMbid: String,
    val totalListenCount: Long,
    val totalUserCount: Long?,
)

/**
 * Batch artist popularity from POST /1/popularity/artist.
 *
 * Same null-vs-zero handling as [ListenBrainzRecordingPopularity]: the parser drops entries
 * whose `total_listen_count` is JSON-null, so [totalListenCount] here is always genuine.
 */
internal data class ListenBrainzArtistPopularity(
    val artistMbid: String,
    val totalListenCount: Long,
    val totalUserCount: Long?,
)

/** Top release group from GET /1/popularity/top-release-groups-for-artist/{mbid}. */
internal data class ListenBrainzTopReleaseGroup(
    val releaseGroupMbid: String,
    val releaseGroupName: String,
    val artistName: String,
    val listenCount: Long,
)

/** A track from the LB Radio JSPF playlist response. */
internal data class ListenBrainzRadioTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val recordingMbid: String? = null,
    val artistMbid: String? = null,
    val releaseMbid: String? = null,
)
