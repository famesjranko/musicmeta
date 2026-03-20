package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.http.HttpClient
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
            httpClient.fetchJson(url)
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
            )
        }
    }

    private fun String.takeIfNotEmpty(): String? = takeIf { it.isNotBlank() }

    private companion object {
        const val BASE_URL = "https://api.deezer.com"
    }
}
