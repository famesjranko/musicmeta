package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.SimilarArtist

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

    fun toGenre(tags: List<String>): EnrichmentData.Metadata =
        EnrichmentData.Metadata(genres = tags)

    fun toBiography(bio: String): EnrichmentData.Biography =
        EnrichmentData.Biography(text = bio, source = "Last.fm")

    fun toPopularity(info: LastFmArtistInfo): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            listenerCount = info.listeners,
            listenCount = info.playcount,
        )
}
