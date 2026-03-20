package com.cascade.enrichment.provider.fanarttv

import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter
import org.json.JSONObject

/**
 * Fanart.tv API client. Requires a project API key.
 * Provides high-quality artist images: thumbnails, backgrounds, logos, banners.
 */
class FanartTvApi(
    private val projectKeyProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(projectKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ projectKey }, httpClient, rateLimiter)

    suspend fun getArtistImages(mbid: String): FanartTvArtistImages? {
        val url = "$BASE_URL/$mbid?api_key=${projectKeyProvider()}"
        val json = rateLimiter.execute { httpClient.fetchJson(url) } ?: return null
        return parseArtistImages(json)
    }

    private fun parseArtistImages(json: JSONObject): FanartTvArtistImages {
        // Album images are nested inside "albums" → {mbid} → "albumcover"/"cdart"
        val albumCovers = mutableListOf<String>()
        val cdArt = mutableListOf<String>()
        val albums = json.optJSONObject("albums")
        if (albums != null) {
            for (key in albums.keys()) {
                val album = albums.optJSONObject(key) ?: continue
                albumCovers.addAll(extractUrls(album, "albumcover"))
                cdArt.addAll(extractUrls(album, "cdart"))
            }
        }
        return FanartTvArtistImages(
            thumbnails = extractUrls(json, "artistthumb"),
            backgrounds = extractUrls(json, "artistbackground"),
            logos = extractUrls(json, "hdmusiclogo"),
            banners = extractUrls(json, "musicbanner"),
            albumCovers = albumCovers,
            cdArt = cdArt,
        )
    }

    private fun extractUrls(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.getJSONObject(i).optString("url").takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        const val BASE_URL = "https://webservice.fanart.tv/v3/music"
    }
}
