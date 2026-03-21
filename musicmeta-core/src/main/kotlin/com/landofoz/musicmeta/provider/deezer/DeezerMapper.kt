package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TrackInfo

/** Maps Deezer DTOs to EnrichmentData subclasses. */
object DeezerMapper {

    fun toArtwork(result: DeezerAlbumResult): EnrichmentData.Artwork? {
        val url = result.coverXl ?: result.coverBig
            ?: result.coverMedium ?: result.coverSmall
            ?: return null
        return EnrichmentData.Artwork(url = url, thumbnailUrl = result.coverMedium)
    }

    fun toDiscography(albums: List<DeezerArtistAlbum>): EnrichmentData.Discography =
        EnrichmentData.Discography(
            albums = albums.map { album ->
                DiscographyAlbum(
                    title = album.title,
                    year = album.releaseDate?.take(4),
                    type = album.recordType,
                    thumbnailUrl = album.coverMedium ?: album.coverSmall,
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", album.id.toString()),
                )
            },
        )

    fun toTracklist(tracks: List<DeezerTrack>): EnrichmentData.Tracklist =
        EnrichmentData.Tracklist(
            tracks = tracks.map { track ->
                TrackInfo(
                    title = track.title,
                    position = track.trackPosition,
                    durationMs = track.durationSec.toLong() * 1000,
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", track.id.toString()),
                )
            },
        )

    fun toSearchCandidate(
        result: DeezerAlbumResult,
        providerId: String,
        score: Int,
    ): SearchCandidate = SearchCandidate(
        title = result.title,
        artist = result.artistName,
        year = null,
        country = null,
        releaseType = null,
        score = score,
        thumbnailUrl = result.coverMedium ?: result.coverSmall,
        identifiers = EnrichmentIdentifiers(),
        provider = providerId,
    )
}
