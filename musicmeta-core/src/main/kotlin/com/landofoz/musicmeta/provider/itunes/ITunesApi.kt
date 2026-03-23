package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.takeIfNotEmpty
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import java.net.URLEncoder

/**
 * iTunes Search API. Free, no authentication required.
 * Rate limit: ~20 requests/minute.
 */
class ITunesApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchAlbum(term: String): ITunesAlbumResult? =
        searchAlbums(term, 1).firstOrNull()

    suspend fun searchAlbums(term: String, limit: Int): List<ITunesAlbumResult> {
        val encoded = URLEncoder.encode(term, "UTF-8")
        val url = "$BASE_URL/search?media=music&entity=album&term=$encoded&limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val album = results.getJSONObject(i)
            parseAlbumResult(album)
        }
    }

    suspend fun lookupAlbumTracks(collectionId: Long): List<ITunesTrackResult> {
        val url = "$BASE_URL/lookup?id=$collectionId&entity=song"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            if (item.optString("wrapperType") != "track") return@mapNotNull null
            ITunesTrackResult(
                trackId = item.optLong("trackId"),
                trackName = item.optString("trackName", ""),
                trackNumber = item.optInt("trackNumber", 0),
                trackTimeMillis = item.optLong("trackTimeMillis").takeIf { it > 0 },
                artistName = item.optString("artistName", ""),
                collectionName = item.optString("collectionName").takeIfNotEmpty(),
            )
        }
    }

    suspend fun lookupArtistAlbums(artistId: Long): List<ITunesAlbumResult> {
        val url = "$BASE_URL/lookup?id=$artistId&entity=album"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            if (item.optString("wrapperType") != "collection") return@mapNotNull null
            parseAlbumResult(item)
        }
    }

    suspend fun searchArtist(artistName: String): Long? {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val url = "$BASE_URL/search?media=music&entity=musicArtist&term=$encoded&limit=1"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val artistId = results.getJSONObject(0).optLong("artistId")
        return if (artistId > 0) artistId else null
    }

    private fun parseAlbumResult(album: org.json.JSONObject): ITunesAlbumResult =
        ITunesAlbumResult(
            collectionId = album.optLong("collectionId"),
            collectionName = album.optString("collectionName", ""),
            artistName = album.optString("artistName", ""),
            artworkUrl = album.optString("artworkUrl100").takeIfNotEmpty(),
            releaseDate = album.optString("releaseDate").takeIfNotEmpty(),
            primaryGenreName = album.optString("primaryGenreName").takeIfNotEmpty(),
            country = album.optString("country").takeIfNotEmpty(),
            trackCount = album.optInt("trackCount", 0).takeIf { it > 0 },
        )

    companion object {
        const val BASE_URL = "https://itunes.apple.com"
    }
}
