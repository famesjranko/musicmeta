package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.provider.encodeQueryValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for the LRCLIB lyrics API (https://lrclib.net).
 * Provides exact match and search endpoints for synced/plain lyrics.
 */
internal class LrcLibApi(
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
        durationSec: Double? = null,
    ): LrcLibResult? = rateLimiter.execute {
        val url = buildString {
            append("$BASE_URL/api/get?")
            append("artist_name=${encodeQueryValue(artist)}")
            append("&track_name=${encodeQueryValue(track)}")
            if (album != null) append("&album_name=${encodeQueryValue(album)}")
            if (durationSec != null) append("&duration=$durationSec")
        }
        val json = httpClient.fetchJsonResult(url).bodyOrThrowTransient() ?: return@execute null
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
        val url = "$BASE_URL/api/search?artist_name=${encodeQueryValue(artist)}&track_name=${encodeQueryValue(track)}"
        val jsonArray = httpClient.fetchJsonArrayResult(url).bodyOrThrowTransient()
            ?: return@execute emptyList()
        parseResultArray(jsonArray)
    }

    private fun parseResult(json: JSONObject): LrcLibResult = LrcLibResult(
        id = json.optLong("id", 0L),
        trackName = json.optString("trackName", ""),
        artistName = json.optString("artistName", ""),
        albumName = json.optString("albumName", null),
        duration = if (json.isNull("duration")) null else json.optDouble("duration", 0.0),
        instrumental = json.optBoolean("instrumental", false),
        syncedLyrics = json.optString("syncedLyrics", null),
        plainLyrics = json.optString("plainLyrics", null),
    )

    private fun parseResultArray(jsonArray: JSONArray): List<LrcLibResult> =
        (0 until jsonArray.length()).map { parseResult(jsonArray.getJSONObject(it)) }

    companion object {
        const val BASE_URL = "https://lrclib.net"
    }
}
