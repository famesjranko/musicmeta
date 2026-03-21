package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.PopularTrack

/** Maps ListenBrainz responses to EnrichmentData subclasses. */
object ListenBrainzMapper {

    fun toPopularity(tracks: List<ListenBrainzPopularTrack>): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            topTracks = tracks.mapIndexed { index, track ->
                PopularTrack(
                    title = track.title,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = track.recordingMbid),
                    listenCount = track.listenCount,
                    rank = index + 1,
                )
            },
        )

    fun toTrackPopularity(
        recordings: List<ListenBrainzRecordingPopularity>,
    ): EnrichmentData.Popularity {
        val first = recordings.firstOrNull() ?: return EnrichmentData.Popularity()
        return EnrichmentData.Popularity(
            listenCount = first.totalListenCount,
            listenerCount = first.totalUserCount,
        )
    }

    fun toArtistPopularity(
        artists: List<ListenBrainzArtistPopularity>,
    ): EnrichmentData.Popularity {
        val first = artists.firstOrNull() ?: return EnrichmentData.Popularity()
        return EnrichmentData.Popularity(
            listenCount = first.totalListenCount,
            listenerCount = first.totalUserCount,
        )
    }

    fun toDiscography(
        groups: List<ListenBrainzTopReleaseGroup>,
    ): EnrichmentData.Discography =
        EnrichmentData.Discography(
            albums = groups.map { group ->
                DiscographyAlbum(
                    title = group.releaseGroupName,
                    identifiers = EnrichmentIdentifiers(
                        musicBrainzReleaseGroupId = group.releaseGroupMbid,
                    ),
                )
            },
        )
}
