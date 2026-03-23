package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.SimilarTrack

/** Maps Last.fm DTOs to EnrichmentData subclasses. */
object LastFmMapper {

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
            GenreTag(name = tag, confidence = 0.3f, sources = listOf("lastfm"))
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
                GenreTag(name = tag, confidence = 0.3f, sources = listOf("lastfm"))
            }.takeIf { it.isNotEmpty() },
        )

    fun toBiography(bio: String): EnrichmentData.Biography =
        EnrichmentData.Biography(text = bio, source = "Last.fm")

    fun toPopularity(info: LastFmArtistInfo): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            listenerCount = info.listeners,
            listenCount = info.playcount,
        )

    fun toTrackPopularity(info: LastFmTrackInfo): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            listenCount = info.playcount,
            listenerCount = info.listeners,
        )
}
