package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowAuth
import org.json.JSONObject

/**
 * Fanart.tv API client. Requires a project API key.
 * Provides high-quality artist images: thumbnails, backgrounds, logos, banners.
 */
internal class FanartTvApi(
    private val projectKeyProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(projectKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ projectKey }, httpClient, rateLimiter)

    /**
     * Fetches album-specific images for a release group from the Fanart.tv album endpoint.
     * URL: /v3/music/albums/{releaseGroupMbid}
     * Response: { "{releaseGroupMbid}": { "albumcover": [...], "cdart": [...] } }
     * Returns null if the release group is not found or has no images.
     */
    suspend fun getAlbumImages(releaseGroupMbid: String): FanartTvAlbumImages? {
        val url = "$BASE_URL/albums/$releaseGroupMbid?api_key=${projectKeyProvider()}"
        val json = rateLimiter.execute { httpClient.fetchJsonResult(url).bodyOrThrowAuth() } ?: return null
        val albumObj = json.optJSONObject(releaseGroupMbid) ?: return null
        return FanartTvAlbumImages(
            albumCovers = extractImages(albumObj, "albumcover"),
            cdArt = extractImages(albumObj, "cdart"),
        )
    }

    suspend fun getArtistImages(mbid: String): FanartTvArtistImages? {
        val url = "$BASE_URL/$mbid?api_key=${projectKeyProvider()}"
        val json = rateLimiter.execute { httpClient.fetchJsonResult(url).bodyOrThrowAuth() } ?: return null
        return parseArtistImages(json)
    }

    // The artist document's nested "albums" map is not read: it merges albumcover/cdart across
    // every album under the artist, so album art is taken from getAlbumImages() only.
    private fun parseArtistImages(json: JSONObject) = FanartTvArtistImages(
        thumbnails = extractImages(json, "artistthumb"),
        backgrounds = extractImages(json, "artistbackground"),
        logos = extractImages(json, "hdmusiclogo"),
        banners = extractImages(json, "musicbanner"),
    )

    private fun extractImages(json: JSONObject, key: String): List<FanartTvImage> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FanartTvImage(
                url = url,
                id = obj.optString("id").takeIf { it.isNotBlank() },
                likes = obj.optString("likes", "0").toIntOrNull() ?: 0,
            )
        }
    }

    private companion object {
        const val BASE_URL = "https://webservice.fanart.tv/v3/music"
    }
}
