package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TrackInfo

/** Maps iTunes DTOs to EnrichmentData subclasses. */
object ITunesMapper {

    fun toArtwork(result: ITunesAlbumResult, artworkSize: Int): EnrichmentData.Artwork? {
        val artworkUrl = result.artworkUrl ?: return null
        val highResUrl = artworkUrl.replace("100x100bb", "${artworkSize}x${artworkSize}bb")
        val sizes = listOf(250, 500, 1000, 3000).map { size ->
            ArtworkSize(
                url = artworkUrl.replace("100x100bb", "${size}x${size}bb"),
                width = size,
                height = size,
                label = "${size}px",
            )
        }
        return EnrichmentData.Artwork(url = highResUrl, thumbnailUrl = artworkUrl, sizes = sizes)
    }

    fun toAlbumMetadata(result: ITunesAlbumResult): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            trackCount = result.trackCount,
            genres = listOfNotNull(result.primaryGenreName),
            genreTags = result.primaryGenreName?.let {
                listOf(GenreTag(it, 0.2f, listOf("itunes")))
            },
            country = result.country,
            releaseDate = result.releaseDate,
        )

    fun toTracklist(tracks: List<ITunesTrackResult>): EnrichmentData.Tracklist =
        EnrichmentData.Tracklist(
            tracks = tracks.map { track ->
                TrackInfo(
                    title = track.trackName,
                    position = track.trackNumber,
                    durationMs = track.trackTimeMillis,
                )
            },
        )

    fun toDiscography(albums: List<ITunesAlbumResult>): EnrichmentData.Discography =
        EnrichmentData.Discography(
            albums = albums.map { album ->
                DiscographyAlbum(
                    title = album.collectionName,
                    year = album.releaseDate?.take(4),
                    thumbnailUrl = album.artworkUrl,
                    identifiers = if (album.collectionId > 0) {
                        EnrichmentIdentifiers().withExtra("itunesCollectionId", album.collectionId.toString())
                    } else {
                        EnrichmentIdentifiers()
                    },
                )
            },
        )

    fun toSearchCandidate(
        result: ITunesAlbumResult,
        providerId: String,
        score: Int,
    ): SearchCandidate {
        val year = result.releaseDate?.take(4)
        return SearchCandidate(
            title = result.collectionName,
            artist = result.artistName,
            year = year,
            country = result.country,
            releaseType = null,
            score = score,
            thumbnailUrl = result.artworkUrl,
            identifiers = EnrichmentIdentifiers(),
            provider = providerId,
        )
    }
}
