package com.cascade.enrichment.provider.listenbrainz

import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter
import org.json.JSONArray

/**
 * Fetches track popularity data from the ListenBrainz API.
 * Uses the top-recordings-for-artist endpoint with artist MusicBrainz IDs.
 */
class ListenBrainzApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun getTopRecordingsForArtist(
        artistMbid: String,
    ): List<ListenBrainzPopularTrack> = rateLimiter.execute {
        val url = "$BASE_URL/popularity/top-recordings-for-artist/$artistMbid"
        val jsonArray = httpClient.fetchJsonArray(url)
            ?: return@execute emptyList()
        parseRecordings(jsonArray)
    }

    private fun parseRecordings(jsonArray: JSONArray): List<ListenBrainzPopularTrack> {
        val results = mutableListOf<ListenBrainzPopularTrack>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val mbid = item.optString("recording_mbid").takeIf { it.isNotBlank() }
                ?: continue
            results += ListenBrainzPopularTrack(
                recordingMbid = mbid,
                title = item.optString("track_name", ""),
                artistName = item.optString("artist_name", ""),
                listenCount = item.optLong("total_listen_count", 0L),
            )
        }
        return results
    }

    private companion object {
        const val BASE_URL = "https://api.listenbrainz.org/1"
    }
}
