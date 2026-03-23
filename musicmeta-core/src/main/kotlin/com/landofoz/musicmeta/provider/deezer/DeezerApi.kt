package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.takeIfNotEmpty
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import java.net.URLEncoder

/**
 * Deezer public search API. No authentication required.
 * Rate limit: ~10 requests/second.
 */
class DeezerApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchAlbum(query: String): DeezerAlbumResult? =
        searchAlbums(query, 1).firstOrNull()

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
                artistName = artist?.optString("name", "") ?: "",
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

    suspend fun searchArtist(name: String): DeezerArtistSearchResult? {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$BASE_URL/search/artist?q=$encoded&limit=1"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null

        val data = json.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val artist = data.getJSONObject(0)
        return DeezerArtistSearchResult(
            id = artist.optLong("id"),
            name = artist.optString("name", ""),
        )
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
                artistName = artist?.optString("name", "") ?: "",
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    private companion object {
        const val BASE_URL = "https://api.deezer.com"
    }
}
