package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Client for the LRCLIB lyrics API (https://lrclib.net).
 * Provides exact match and search endpoints for synced/plain lyrics.
 */
class LrcLibApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {
    /**
     * Exact match lookup. Returns a single result or null if not found.
     * Uses album and duration for more precise matching when available.
     */
    suspend fun getLyrics(
        artist: String,
        track: String,
        album: String? = null,
        durationSec: Int? = null,
    ): LrcLibResult? = rateLimiter.execute {
        val url = buildString {
            append("$BASE_URL/api/get?")
            append("artist_name=${encode(artist)}")
            append("&track_name=${encode(track)}")
            if (album != null) append("&album_name=${encode(album)}")
            if (durationSec != null) append("&duration=$durationSec")
        }
        val json = httpClient.fetchJson(url) ?: return@execute null
        parseResult(json)
    }

    /**
     * Search for lyrics matching artist and track name.
     * Returns multiple candidates ranked by relevance.
     */
    suspend fun searchLyrics(
        artist: String,
        track: String,
    ): List<LrcLibResult> = rateLimiter.execute {
        val url = "$BASE_URL/api/search?artist_name=${encode(artist)}&track_name=${encode(track)}"
        val jsonArray = httpClient.fetchJsonArray(url) ?: return@execute emptyList()
        parseResultArray(jsonArray)
    }

    private fun parseResult(json: JSONObject): LrcLibResult = LrcLibResult(
        id = json.getLong("id"),
        trackName = json.getString("trackName"),
        artistName = json.getString("artistName"),
        albumName = json.optString("albumName", null),
        duration = if (json.isNull("duration")) null else json.getDouble("duration"),
        instrumental = json.optBoolean("instrumental", false),
        syncedLyrics = json.optString("syncedLyrics", null),
        plainLyrics = json.optString("plainLyrics", null),
    )

    private fun parseResultArray(jsonArray: JSONArray): List<LrcLibResult> =
        (0 until jsonArray.length()).map { parseResult(jsonArray.getJSONObject(it)) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val BASE_URL = "https://lrclib.net"
    }
}
