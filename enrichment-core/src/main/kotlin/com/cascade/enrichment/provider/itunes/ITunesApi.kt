package com.cascade.enrichment.provider.itunes

import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter
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
            httpClient.fetchJson(url)
        } ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val album = results.getJSONObject(i)
            ITunesAlbumResult(
                collectionId = album.optLong("collectionId"),
                collectionName = album.optString("collectionName", ""),
                artistName = album.optString("artistName", ""),
                artworkUrl = album.optString("artworkUrl100").takeIfNotEmpty(),
                releaseDate = album.optString("releaseDate").takeIfNotEmpty(),
                primaryGenreName = album.optString("primaryGenreName").takeIfNotEmpty(),
                country = album.optString("country").takeIfNotEmpty(),
            )
        }
    }

    private fun String.takeIfNotEmpty(): String? = takeIf { it.isNotBlank() }

    private companion object {
        const val BASE_URL = "https://itunes.apple.com"
    }
}
