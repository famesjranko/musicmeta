package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import com.landofoz.musicmeta.RadioTrack
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.TopTrack

/** Maps ListenBrainz responses to EnrichmentData subclasses. */
internal object ListenBrainzMapper {

    fun toTrackPopularity(
        recordings: List<ListenBrainzRecordingPopularity>,
    ): EnrichmentData.Popularity {
        val first = recordings.firstOrNull() ?: return EnrichmentData.Popularity()
        return EnrichmentData.Popularity(
            listenCount = first.totalListenCount,
            listenerCount = first.totalUserCount,
            signals = countSignals(first.totalListenCount, first.totalUserCount),
        )
    }

    fun toArtistPopularity(
        artists: List<ListenBrainzArtistPopularity>,
    ): EnrichmentData.Popularity {
        val first = artists.firstOrNull() ?: return EnrichmentData.Popularity()
        return EnrichmentData.Popularity(
            listenCount = first.totalListenCount,
            listenerCount = first.totalUserCount,
            signals = countSignals(first.totalListenCount, first.totalUserCount),
        )
    }

    /**
     * ListenBrainz's two counts as signals. A ListenBrainz listen is not a Last.fm scrobble, so each
     * carries its source rather than being blended into a single number here.
     */
    private fun countSignals(listens: Long?, users: Long?): List<PopularitySignal> =
        listOfNotNull(
            listens?.let {
                PopularitySignal(SOURCE, PopularitySignalKind.LISTEN_COUNT, it.toDouble())
            },
            users?.let {
                PopularitySignal(SOURCE, PopularitySignalKind.LISTENER_COUNT, it.toDouble())
            },
        )

    private const val SOURCE = "listenbrainz"

    /** The half in "scaled, then halved" — see [toSimilarArtists] for what it buys. */
    private const val CORROBORATION_WEIGHT = 0.5f

    fun toTopTracks(tracks: List<ListenBrainzPopularTrack>): EnrichmentData.TopTracks =
        EnrichmentData.TopTracks(
            tracks = tracks.mapIndexed { index, t ->
                TopTrack(
                    title = t.title,
                    artist = t.artistName,
                    album = t.albumName,
                    durationMs = t.durationMs,
                    listenCount = t.listenCount,
                    listenerCount = t.listenerCount,
                    rank = index + 1,
                    sources = listOf("listenbrainz"),
                    identifiers = EnrichmentIdentifiers(musicBrainzId = t.recordingMbid),
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

    /**
     * Labs neighbours as similar artists, every one carrying the MusicBrainz artist id it was
     * keyed on.
     *
     * The upstream score is an unbounded session count with no ceiling to divide by, so it is
     * scaled against the highest score in the same answer and then halved. **The contract the
     * halving keeps: nothing from this provider alone reaches the top of a merged list.**
     * `SimilarArtistMerger` sums `matchScore` across providers, and this provider answers with up
     * to a hundred rows against Last.fm's twenty — left at full scale a row this provider alone
     * chose would sum above two providers that agree on an artist neither ranked first. At half
     * scale a Labs row still lifts an artist another provider also chose, and can no longer
     * outrank two providers that agree.
     *
     * A score is therefore comparable only within one answer: not against another answer's, and not
     * against Last.fm's own similarity figure.
     */
    fun toSimilarArtists(artists: List<ListenBrainzSimilarArtist>): EnrichmentData.SimilarArtists {
        val topScore = artists.maxOfOrNull { it.score }?.takeIf { it > 0 }
        return EnrichmentData.SimilarArtists(
            artists = artists.map { artist ->
                SimilarArtist(
                    name = artist.name,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = artist.artistMbid),
                    matchScore = topScore?.let { artist.score.toFloat() / it * CORROBORATION_WEIGHT } ?: 0f,
                    sources = listOf(SOURCE),
                )
            },
        )
    }

    fun toRadioPlaylist(tracks: List<ListenBrainzRadioTrack>): EnrichmentData.RadioPlaylist =
        EnrichmentData.RadioPlaylist(
            tracks = tracks.map { track ->
                RadioTrack(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = track.recordingMbid)
                        .let { ids -> track.artistMbid?.let { ids.with(IdentifierNamespace.MUSICBRAINZ_ARTIST, it) }
                            ?: ids }
                        .let { ids -> track.releaseMbid?.let { ids.with(IdentifierNamespace.MUSICBRAINZ_RELEASE, it) }
                            ?: ids },
                )
            },
        )
}
