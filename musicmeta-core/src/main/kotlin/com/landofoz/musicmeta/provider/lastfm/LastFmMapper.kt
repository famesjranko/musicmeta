package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
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
                )
            },
        )

    fun toGenre(tags: List<String>): EnrichmentData.Metadata =
        EnrichmentData.Metadata(genres = tags)

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
