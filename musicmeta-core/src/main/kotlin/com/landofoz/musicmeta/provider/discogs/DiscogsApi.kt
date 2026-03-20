package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Discogs API client. Requires a personal access token.
 * Rate limited to 60 requests/minute (1000ms interval).
 */
class DiscogsApi(
    private val tokenProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(personalToken: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ personalToken }, httpClient, rateLimiter)

    suspend fun searchRelease(title: String, artist: String): DiscogsRelease? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val url = "$BASE_URL?type=release&title=$encodedTitle" +
            "&artist=$encodedArtist&token=${tokenProvider()}"
        val json = rateLimiter.execute { httpClient.fetchJson(url) } ?: return null
        return parseFirstResult(json)
    }

    private fun parseFirstResult(json: JSONObject): DiscogsRelease? {
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        val labels = first.optJSONArray("label")
        val label = if (labels != null && labels.length() > 0) {
            labels.getString(0)
        } else {
            null
        }
        return DiscogsRelease(
            title = first.optString("title", ""),
            label = label,
            year = first.optString("year").takeIf { it.isNotBlank() },
            country = first.optString("country").takeIf { it.isNotBlank() },
            coverImage = first.optString("cover_image").takeIf { it.isNotBlank() },
            releaseType = first.optString("type").takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val BASE_URL = "https://api.discogs.com/database/search"
    }
}
