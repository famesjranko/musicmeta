package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches track popularity data from the ListenBrainz API.
 * Uses top-recordings, batch recording/artist popularity, and top release groups endpoints.
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

    /** Batch recording popularity via POST /1/popularity/recording. */
    suspend fun getRecordingPopularity(
        recordingMbids: List<String>,
    ): List<ListenBrainzRecordingPopularity> = rateLimiter.execute {
        val body = JSONObject().put("recording_mbids", JSONArray(recordingMbids)).toString()
        val jsonArray = httpClient.postJsonArray("$BASE_URL/popularity/recording", body)
            ?: return@execute emptyList()
        parseRecordingPopularity(jsonArray)
    }

    /** Batch artist popularity via POST /1/popularity/artist. */
    suspend fun getArtistPopularity(
        artistMbids: List<String>,
    ): List<ListenBrainzArtistPopularity> = rateLimiter.execute {
        val body = JSONObject().put("artist_mbids", JSONArray(artistMbids)).toString()
        val jsonArray = httpClient.postJsonArray("$BASE_URL/popularity/artist", body)
            ?: return@execute emptyList()
        parseArtistPopularity(jsonArray)
    }

    /** GET /1/popularity/top-release-groups-for-artist/{mbid}. */
    suspend fun getTopReleaseGroupsForArtist(
        artistMbid: String,
    ): List<ListenBrainzTopReleaseGroup> = rateLimiter.execute {
        val url = "$BASE_URL/popularity/top-release-groups-for-artist/$artistMbid"
        val jsonArray = httpClient.fetchJsonArray(url)
            ?: return@execute emptyList()
        parseTopReleaseGroups(jsonArray)
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

    private fun parseRecordingPopularity(
        jsonArray: JSONArray,
    ): List<ListenBrainzRecordingPopularity> {
        val results = mutableListOf<ListenBrainzRecordingPopularity>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val mbid = item.optString("recording_mbid").takeIf { it.isNotBlank() }
                ?: continue
            results += ListenBrainzRecordingPopularity(
                recordingMbid = mbid,
                totalListenCount = item.optLong("total_listen_count", 0L),
                totalUserCount = item.optLong("total_user_count", 0L),
            )
        }
        return results
    }

    private fun parseArtistPopularity(
        jsonArray: JSONArray,
    ): List<ListenBrainzArtistPopularity> {
        val results = mutableListOf<ListenBrainzArtistPopularity>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val mbid = item.optString("artist_mbid").takeIf { it.isNotBlank() }
                ?: continue
            results += ListenBrainzArtistPopularity(
                artistMbid = mbid,
                totalListenCount = item.optLong("total_listen_count", 0L),
                totalUserCount = item.optLong("total_user_count", 0L),
            )
        }
        return results
    }

    private fun parseTopReleaseGroups(
        jsonArray: JSONArray,
    ): List<ListenBrainzTopReleaseGroup> {
        val results = mutableListOf<ListenBrainzTopReleaseGroup>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val mbid = item.optString("release_group_mbid").takeIf { it.isNotBlank() }
                ?: continue
            results += ListenBrainzTopReleaseGroup(
                releaseGroupMbid = mbid,
                releaseGroupName = item.optString("release_group_name", ""),
                artistName = item.optString("artist_name", ""),
                listenCount = item.optLong("total_listen_count", 0L),
            )
        }
        return results
    }

    companion object {
        const val BASE_URL = "https://api.listenbrainz.org/1"
    }
}
