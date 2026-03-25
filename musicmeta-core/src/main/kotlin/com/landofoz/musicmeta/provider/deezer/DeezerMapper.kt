package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.RadioTrack
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.SimilarAlbum
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.SimilarTrack
import com.landofoz.musicmeta.TopTrack
import com.landofoz.musicmeta.TrackInfo

/** Maps Deezer DTOs to EnrichmentData subclasses. */
object DeezerMapper {

    fun toArtwork(result: DeezerAlbumResult): EnrichmentData.Artwork? {
        val url = result.coverXl ?: result.coverBig
            ?: result.coverMedium ?: result.coverSmall
            ?: return null
        val sizes = listOfNotNull(
            result.coverSmall?.let { ArtworkSize(url = it, width = 56, height = 56, label = "small") },
            result.coverMedium?.let { ArtworkSize(url = it, width = 250, height = 250, label = "medium") },
            result.coverBig?.let { ArtworkSize(url = it, width = 500, height = 500, label = "big") },
            result.coverXl?.let { ArtworkSize(url = it, width = 1000, height = 1000, label = "xl") },
        )
        return EnrichmentData.Artwork(
            url = url,
            thumbnailUrl = result.coverMedium,
            sizes = sizes.takeIf { it.isNotEmpty() },
        )
    }

    fun toArtistPhoto(result: DeezerArtistSearchResult): EnrichmentData.Artwork? {
        val url = result.pictureXl ?: result.pictureBig
            ?: result.pictureMedium ?: result.pictureSmall
            ?: return null
        val sizes = listOfNotNull(
            result.pictureSmall?.let { ArtworkSize(url = it, width = 56, height = 56, label = "small") },
            result.pictureMedium?.let { ArtworkSize(url = it, width = 250, height = 250, label = "medium") },
            result.pictureBig?.let { ArtworkSize(url = it, width = 500, height = 500, label = "big") },
            result.pictureXl?.let { ArtworkSize(url = it, width = 1000, height = 1000, label = "xl") },
        )
        return EnrichmentData.Artwork(
            url = url,
            thumbnailUrl = result.pictureMedium,
            sizes = sizes.takeIf { it.isNotEmpty() },
        )
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

    fun toAlbumMetadata(result: DeezerAlbumResult): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            trackCount = result.nbTracks,
            releaseType = result.recordType,
            explicit = result.explicitLyrics,
        )

    fun toSimilarArtists(artists: List<DeezerRelatedArtist>): EnrichmentData.SimilarArtists {
        val count = artists.size.coerceAtLeast(1)
        return EnrichmentData.SimilarArtists(
            artists = artists.mapIndexed { index, artist ->
                SimilarArtist(
                    name = artist.name,
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString()),
                    matchScore = 1.0f - (index.toFloat() / count) * 0.9f,
                    sources = listOf("deezer"),
                )
            },
        )
    }

    fun toSimilarTracks(tracks: List<DeezerRadioTrack>): EnrichmentData.SimilarTracks {
        val count = tracks.size.coerceAtLeast(1)
        return EnrichmentData.SimilarTracks(
            tracks = tracks.mapIndexed { index, track ->
                SimilarTrack(
                    title = track.title,
                    artist = track.artistName,
                    matchScore = 1.0f - (index.toFloat() / count) * 0.9f,
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", track.id.toString()),
                    sources = listOf("deezer"),
                )
            },
        )
    }

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

    fun toTopTracks(tracks: List<DeezerTopTrack>): EnrichmentData.TopTracks =
        EnrichmentData.TopTracks(
            tracks = tracks.mapIndexed { index, t ->
                TopTrack(
                    title = t.title,
                    artist = t.artistName,
                    album = t.albumTitle,
                    durationMs = if (t.durationSec > 0) t.durationSec.toLong() * 1000 else null,
                    rank = index + 1,
                    sources = listOf("deezer"),
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", t.id.toString()),
                )
            },
        )

    fun toRadioPlaylist(tracks: List<DeezerRadioTrack>): EnrichmentData.RadioPlaylist =
        EnrichmentData.RadioPlaylist(
            tracks = tracks.map { track ->
                RadioTrack(
                    title = track.title,
                    artist = track.artistName,
                    album = track.albumTitle,
                    durationMs = if (track.durationSec > 0) track.durationSec.toLong() * 1000 else null,
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", track.id.toString()),
                )
            },
        )

    fun toTrackPreview(result: DeezerTrackSearchResult): EnrichmentData.TrackPreview? {
        val url = result.previewUrl ?: return null
        return EnrichmentData.TrackPreview(
            url = url,
            durationMs = 30000L, // Deezer previews are always 30 seconds
            source = "deezer",
        )
    }

    fun toSimilarAlbum(
        album: DeezerArtistAlbum,
        artistName: String,
        score: Float,
    ): SimilarAlbum = SimilarAlbum(
        title = album.title,
        artist = artistName,
        year = album.releaseDate?.take(4)?.toIntOrNull(),
        artistMatchScore = score,
        thumbnailUrl = album.coverMedium ?: album.coverSmall,
        identifiers = EnrichmentIdentifiers().withExtra("deezerId", album.id.toString()),
    )
}
