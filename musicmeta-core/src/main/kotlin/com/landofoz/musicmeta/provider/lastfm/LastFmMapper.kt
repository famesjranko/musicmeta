package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.SimilarTrack
import com.landofoz.musicmeta.TopTrack

/** Maps Last.fm DTOs to EnrichmentData subclasses. */
internal object LastFmMapper {

    fun toSimilarArtists(artists: List<LastFmSimilarArtist>): EnrichmentData.SimilarArtists =
        EnrichmentData.SimilarArtists(
            artists = artists.map {
                SimilarArtist(
                    name = it.name,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = it.mbid),
                    matchScore = it.matchScore,
                    sources = listOf("lastfm"),
                )
            },
        )

    fun toSimilarTracks(tracks: List<LastFmSimilarTrack>): EnrichmentData.SimilarTracks =
        EnrichmentData.SimilarTracks(
            tracks = tracks.map {
                SimilarTrack(
                    title = it.title,
                    artist = it.artist,
                    matchScore = it.matchScore,
                    identifiers = if (it.mbid != null) {
                        EnrichmentIdentifiers(musicBrainzId = it.mbid)
                    } else {
                        EnrichmentIdentifiers()
                    },
                    sources = listOf("lastfm"),
                )
            },
        )

    fun toAlbumMetadata(info: LastFmAlbumInfo): EnrichmentData.Metadata {
        val genreTagList = info.tags.map { tag ->
            GenreTag(name = tag, confidence = 0.3f, sources = listOf("lastfm"), curated = false)
        }.takeIf { it.isNotEmpty() }
        return EnrichmentData.Metadata(
            genres = info.tags.takeIf { it.isNotEmpty() },
            genreTags = genreTagList,
            trackCount = info.trackCount,
        )
    }

    fun toGenre(tags: List<String>): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            genres = tags,
            genreTags = tags.map { tag ->
                GenreTag(name = tag, confidence = 0.3f, sources = listOf("lastfm"), curated = false)
            }.takeIf { it.isNotEmpty() },
        )

    fun toBiography(bio: String): EnrichmentData.Biography =
        EnrichmentData.Biography(text = bio, source = "Last.fm")

    /** [LastFmAlbumInfo.wiki] is the album.getInfo `wiki` block's summary — same shape as the
     * artist bio, just off the album payload instead. */
    fun toAlbumDescription(wiki: String): EnrichmentData.Biography =
        EnrichmentData.Biography(text = wiki, source = "Last.fm")

    fun toPopularity(info: LastFmArtistInfo): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            listenerCount = info.listeners,
            listenCount = info.playcount,
            signals = countSignals(info.playcount, info.listeners),
        )

    fun toTopTracks(tracks: List<LastFmTopTrack>): EnrichmentData.TopTracks =
        EnrichmentData.TopTracks(
            tracks = tracks.map { t ->
                TopTrack(
                    title = t.title,
                    artist = t.artist,
                    listenCount = t.playcount,
                    listenerCount = t.listeners,
                    rank = t.rank,
                    sources = listOf("lastfm"),
                    identifiers = if (t.mbid != null) EnrichmentIdentifiers(musicBrainzId = t.mbid)
                    else EnrichmentIdentifiers(),
                )
            },
        )

    fun toTrackPopularity(info: LastFmTrackInfo): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            listenCount = info.playcount,
            listenerCount = info.listeners,
            signals = countSignals(info.playcount, info.listeners),
        )

    /**
     * Last.fm's two counts as signals. `listenCount` is scrobbles, which is not the same unit as
     * ListenBrainz's listens, so it is labelled with its source rather than blended here.
     */
    private fun countSignals(playcount: Long?, listeners: Long?): List<PopularitySignal> =
        listOfNotNull(
            playcount?.let {
                PopularitySignal(SOURCE, PopularitySignalKind.LISTEN_COUNT, it.toDouble())
            },
            listeners?.let {
                PopularitySignal(SOURCE, PopularitySignalKind.LISTENER_COUNT, it.toDouble())
            },
        )

    private const val SOURCE = "lastfm"
}
