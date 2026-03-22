package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.PopularTrack
import com.landofoz.musicmeta.SimilarArtist

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

    fun toSimilarArtists(artists: List<ListenBrainzSimilarArtist>): EnrichmentData.SimilarArtists =
        EnrichmentData.SimilarArtists(
            artists = artists.map { artist ->
                SimilarArtist(
                    name = artist.name,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = artist.artistMbid),
                    matchScore = artist.score,
                    sources = listOf("listenbrainz"),
                )
            },
        )

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
