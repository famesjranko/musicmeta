package com.landofoz.musicmeta.provider.listenbrainz

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
}
