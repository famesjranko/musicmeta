package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Last.fm API client. Requires an API key from https://www.last.fm/api.
 * Rate limited to 5 requests/second (200ms interval).
 */
class LastFmApi(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(apiKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ apiKey }, httpClient, rateLimiter)

    suspend fun getArtistInfo(artistName: String): LastFmArtistInfo? {
        val url = buildUrl("artist.getinfo", artistName)
        val json = rateLimiter.execute { httpClient.fetchJson(url) } ?: return null
        return parseArtistInfo(json)
    }

    suspend fun getSimilarArtists(artistName: String): List<LastFmSimilarArtist> {
        val url = buildUrl("artist.getsimilar", artistName) + "&limit=20"
        val json = rateLimiter.execute { httpClient.fetchJson(url) } ?: return emptyList()
        return parseSimilarArtists(json)
    }

    suspend fun getArtistTopTags(artistName: String): List<String> {
        val url = buildUrl("artist.getinfo", artistName)
        val json = rateLimiter.execute { httpClient.fetchJson(url) } ?: return emptyList()
        return parseTags(json)
    }

    private fun buildUrl(method: String, artistName: String): String {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        return "$BASE_URL?method=$method&artist=$encoded&api_key=${apiKeyProvider()}&format=json"
    }

    private fun parseArtistInfo(json: JSONObject): LastFmArtistInfo? {
        val artist = json.optJSONObject("artist") ?: return null
        val bio = artist.optJSONObject("bio")?.optString("summary")?.takeIf { it.isNotBlank() }
        val stats = artist.optJSONObject("stats")
        return LastFmArtistInfo(
            name = artist.optString("name", ""),
            bio = bio,
            tags = parseTags(json),
            listeners = stats?.optString("listeners")?.toLongOrNull(),
            playcount = stats?.optString("playcount")?.toLongOrNull(),
        )
    }

    private fun parseSimilarArtists(json: JSONObject): List<LastFmSimilarArtist> {
        val container = json.optJSONObject("similarartists") ?: return emptyList()
        val array = container.optJSONArray("artist") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            LastFmSimilarArtist(
                name = obj.optString("name", ""),
                matchScore = obj.optString("match", "0").toFloatOrNull() ?: 0f,
                mbid = obj.optString("mbid").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parseTags(json: JSONObject): List<String> {
        val artist = json.optJSONObject("artist") ?: return emptyList()
        val tags = artist.optJSONObject("tags") ?: return emptyList()
        val tagArray = tags.optJSONArray("tag") ?: return emptyList()
        return (0 until tagArray.length()).map { i ->
            tagArray.getJSONObject(i).optString("name", "")
        }.filter { it.isNotBlank() }
    }

    private companion object {
        const val BASE_URL = "http://ws.audioscrobbler.com/2.0/"
    }
}
