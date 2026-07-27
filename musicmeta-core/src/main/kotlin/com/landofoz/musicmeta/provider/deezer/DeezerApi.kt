package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.takeIfNotEmpty
import java.net.URLEncoder

/** Candidate pool size for artist search — enough hits for a ghost to be outvoted. */
internal const val ARTIST_SEARCH_LIMIT = 10

/**
 * Deezer public search API. No authentication required.
 * Rate limit: ~10 requests/second.
 */
internal class DeezerApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchAlbums(query: String, limit: Int): List<DeezerAlbumResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/album?q=$encoded&limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val album = data.getJSONObject(i)
            val artist = album.optJSONObject("artist")
            DeezerAlbumResult(
                id = album.optLong("id"),
                title = album.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                coverSmall = album.optString("cover_small").takeIfNotEmpty(),
                coverMedium = album.optString("cover_medium").takeIfNotEmpty(),
                coverBig = album.optString("cover_big").takeIfNotEmpty(),
                coverXl = album.optString("cover_xl").takeIfNotEmpty(),
                nbTracks = album.optInt("nb_tracks", 0).takeIf { it > 0 },
                recordType = album.optString("record_type").takeIfNotEmpty(),
                explicitLyrics = if (album.has("explicit_lyrics")) album.optBoolean("explicit_lyrics") else null,
            )
        }
    }

    /**
     * Finds the artist a human would mean by [name].
     *
     * Deezer's result order is not trustworthy: "Radiohead" returns an exact-name ghost
     * (0 albums, 470 fans) ahead of the real artist (id 399). So fetch a pool of candidates,
     * keep only those whose name matches, and break the tie on popularity — an exact name beats
     * a looser one, then more fans, then more albums. Popularity never promotes a non-matching
     * name, so a hugely popular wrong-name artist still loses to a modest right-name one.
     */
    suspend fun searchArtist(name: String): DeezerArtistSearchResult? {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$BASE_URL/search/artist?q=$encoded&limit=$ARTIST_SEARCH_LIMIT"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null

        val data = json.optJSONArray("data") ?: return null
        return (0 until data.length())
            .map { data.getJSONObject(it) }
            .filter { ArtistMatcher.isMatch(name, it.optString("name", "")) }
            .maxWithOrNull(
                compareBy(
                    { ArtistMatcher.isExactMatch(name, it.optString("name", "")) },
                    { it.optLong("nb_fan") },
                    { it.optLong("nb_album") },
                ),
            )
            ?.let { artist ->
                DeezerArtistSearchResult(
                    id = artist.optLong("id"),
                    name = artist.optString("name", ""),
                    pictureSmall = artist.optString("picture_small").takeIfNotEmpty(),
                    pictureMedium = artist.optString("picture_medium").takeIfNotEmpty(),
                    pictureBig = artist.optString("picture_big").takeIfNotEmpty(),
                    pictureXl = artist.optString("picture_xl").takeIfNotEmpty(),
                )
            }
    }

    suspend fun getArtistAlbums(artistId: Long, limit: Int = 50): List<DeezerArtistAlbum> {
        val url = "$BASE_URL/artist/$artistId/albums?limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val album = data.getJSONObject(i)
            DeezerArtistAlbum(
                id = album.optLong("id"),
                title = album.optString("title", ""),
                releaseDate = album.optString("release_date").takeIfNotEmpty(),
                recordType = album.optString("record_type").takeIfNotEmpty(),
                coverSmall = album.optString("cover_small").takeIfNotEmpty(),
                coverMedium = album.optString("cover_medium").takeIfNotEmpty(),
            )
        }
    }

    suspend fun getAlbumTracks(albumId: Long): List<DeezerTrack> {
        val url = "$BASE_URL/album/$albumId/tracks"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            DeezerTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                trackPosition = track.optInt("track_position", 0),
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    suspend fun getRelatedArtists(artistId: Long, limit: Int = 20): List<DeezerRelatedArtist> {
        val url = "$BASE_URL/artist/$artistId/related?limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val artist = data.getJSONObject(i)
            DeezerRelatedArtist(
                id = artist.optLong("id"),
                name = artist.optString("name", ""),
            )
        }
    }

    suspend fun searchTrack(title: String, artist: String): DeezerTrackSearchResult? {
        val query = URLEncoder.encode("$artist $title", "UTF-8")
        val url = "$BASE_URL/search/track?q=$query&limit=5"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null

        val data = json.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val track = data.getJSONObject(i)
            val trackArtist = track.optJSONObject("artist")
            val artistName = trackArtist?.optString("name", "").orEmpty()
            return DeezerTrackSearchResult(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artistName,
                previewUrl = track.optString("preview").takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration").takeIf { it > 0 },
                albumTitle = track.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    suspend fun getTrackRadio(trackId: Long, limit: Int = 25): List<DeezerRadioTrack> {
        val url = "$BASE_URL/track/$trackId/radio?limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val trackArtist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerRadioTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = trackArtist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    suspend fun getArtistTop(artistId: Long, limit: Int = 10): List<DeezerTopTrack> {
        val url = "$BASE_URL/artist/$artistId/top?limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val artist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerTopTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
                rank = track.optInt("rank", 0),
            )
        }
    }

    suspend fun getArtistRadio(artistId: Long, limit: Int = 25): List<DeezerRadioTrack> {
        val url = "$BASE_URL/artist/$artistId/radio?limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val artist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerRadioTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    /** Fetches a single track by Deezer ID. Returns preview URL and metadata. */
    suspend fun getTrack(trackId: Long): DeezerTrackSearchResult? = rateLimiter.execute {
        val url = "$BASE_URL/track/$trackId"
        val json = when (val r = httpClient.fetchJsonResult(url)) {
            is HttpResult.Ok -> r.body
            else -> return@execute null
        }
        val artist = json.optJSONObject("artist")
        DeezerTrackSearchResult(
            id = json.optLong("id"),
            title = json.optString("title", ""),
            artistName = artist?.optString("name", "").orEmpty(),
            previewUrl = json.optString("preview").takeIf { it.isNotBlank() },
            durationSec = json.optInt("duration").takeIf { it > 0 },
            albumTitle = json.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val BASE_URL = "https://api.deezer.com"
    }
}
