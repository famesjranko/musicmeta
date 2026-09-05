package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.drift.BodyShape
import com.landofoz.musicmeta.drift.SchemaTarget
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowAuthOrTransient
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.provider.encodePathSegment
import com.landofoz.musicmeta.provider.encodeQueryValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches track popularity data from the ListenBrainz API.
 * Uses top-recordings, batch recording/artist popularity, and top release groups endpoints.
 */
internal class ListenBrainzApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
    private val authToken: String? = null,
) {

    suspend fun getTopRecordingsForArtist(
        artistMbid: String,
    ): List<ListenBrainzPopularTrack> = rateLimiter.execute {
        val jsonArray = httpClient.fetchJsonArrayResult(topRecordingsUrl(artistMbid)).bodyOrThrowTransient()
            ?: return@execute emptyList()
        parseRecordings(jsonArray)
    }

    /** Batch recording popularity via POST /1/popularity/recording. */
    suspend fun getRecordingPopularity(
        recordingMbids: List<String>,
    ): List<ListenBrainzRecordingPopularity> = rateLimiter.execute {
        val body = JSONObject().put("recording_mbids", JSONArray(recordingMbids)).toString()
        val jsonArray = httpClient.postJsonArrayResult("$BASE_URL/popularity/recording", body).bodyOrThrowTransient()
            ?: return@execute emptyList()
        parseRecordingPopularity(jsonArray)
    }

    /** Batch artist popularity via POST /1/popularity/artist. */
    suspend fun getArtistPopularity(
        artistMbids: List<String>,
    ): List<ListenBrainzArtistPopularity> = rateLimiter.execute {
        val body = JSONObject().put("artist_mbids", JSONArray(artistMbids)).toString()
        val jsonArray = httpClient.postJsonArrayResult("$BASE_URL/popularity/artist", body).bodyOrThrowTransient()
            ?: return@execute emptyList()
        parseArtistPopularity(jsonArray)
    }

    /** GET /1/popularity/top-release-groups-for-artist/{mbid}. */
    suspend fun getTopReleaseGroupsForArtist(
        artistMbid: String,
    ): List<ListenBrainzTopReleaseGroup> = rateLimiter.execute {
        val jsonArray = httpClient.fetchJsonArrayResult(topReleaseGroupsUrl(artistMbid)).bodyOrThrowTransient()
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
                title = item.optString("recording_name", ""),
                artistName = item.optString("artist_name", ""),
                listenCount = item.optLong("total_listen_count", 0L),
                listenerCount = item.optLong("total_user_count", 0L).takeIf { it > 0 },
                durationMs = item.optLong("length", 0L).takeIf { it > 0 },
                albumName = item.optString("release_name")?.takeIf { it.isNotBlank() },
            )
        }
        return results
    }

    /**
     * `POST /1/popularity/recording` returns a non-empty entry even when LB has no data for a
     * recording: `total_listen_count` and `total_user_count` come back as JSON null rather than
     * the key being absent. `optLong(key, 0L)` cannot tell that apart from a genuine `0`, so an
     * entry whose `total_listen_count` is null is dropped here -- it carries no information, and
     * dropping it lets an all-null batch fall through the caller's `isEmpty()` guard to
     * `NotFound` instead of a fake `Success(0, 0)`.
     *
     * A genuine `total_listen_count: 0` is kept: it is real information (nobody has listened
     * to this recording), not an absence of data, so it is not treated the same as null.
     */
    private fun parseRecordingPopularity(
        jsonArray: JSONArray,
    ): List<ListenBrainzRecordingPopularity> =
        (0 until jsonArray.length()).mapNotNull { parseRecordingPopularityItem(jsonArray.getJSONObject(it)) }

    private fun parseRecordingPopularityItem(item: JSONObject): ListenBrainzRecordingPopularity? {
        val mbid = item.optString("recording_mbid").takeIf { it.isNotBlank() } ?: return null
        if (item.isNull("total_listen_count")) return null
        return ListenBrainzRecordingPopularity(
            recordingMbid = mbid,
            totalListenCount = item.optLong("total_listen_count", 0L),
            totalUserCount = optNullableLong(item, "total_user_count"),
        )
    }

    /** Same null-vs-zero handling as [parseRecordingPopularity]; see its KDoc. */
    private fun parseArtistPopularity(
        jsonArray: JSONArray,
    ): List<ListenBrainzArtistPopularity> =
        (0 until jsonArray.length()).mapNotNull { parseArtistPopularityItem(jsonArray.getJSONObject(it)) }

    private fun parseArtistPopularityItem(item: JSONObject): ListenBrainzArtistPopularity? {
        val mbid = item.optString("artist_mbid").takeIf { it.isNotBlank() } ?: return null
        if (item.isNull("total_listen_count")) return null
        return ListenBrainzArtistPopularity(
            artistMbid = mbid,
            totalListenCount = item.optLong("total_listen_count", 0L),
            totalUserCount = optNullableLong(item, "total_user_count"),
        )
    }

    /** `null` when [key] is JSON-null or absent, the value otherwise -- `optLong` cannot tell those apart. */
    private fun optNullableLong(item: JSONObject, key: String): Long? =
        if (item.isNull(key)) null else item.optLong(key, 0L)

    /**
     * Similar artists for [artistMbid] from the Labs host, under the one pinned
     * [SIMILAR_ARTISTS_ALGORITHM].
     *
     * A 4xx here is never an empty answer: the route answers an artist it holds nothing for with
     * `200 []`, so the only thing a rejection can mean is that the request itself is no longer
     * one this route accepts — most likely the pinned algorithm leaving the enum. Every other
     * `*Api` call collapses a `ClientError` to `null` and reads as `NotFound`, which is exactly the
     * silent emptiness a retired algorithm must not produce, so this one throws instead
     * (`docs/pitfalls.md` §31).
     */
    suspend fun getSimilarArtists(
        artistMbid: String,
    ): List<ListenBrainzSimilarArtist> = rateLimiter.execute {
        val url = similarArtistsUrl(artistMbid)
        val result = httpClient.fetchJsonArrayResult(url)
        if (result is HttpResult.ClientError) {
            throw LabsRequestRejectedException(rejectionMessage(result.statusCode))
        }
        val jsonArray = result.bodyOrThrowTransient() ?: return@execute emptyList()
        parseSimilarArtists(jsonArray)
    }

    /**
     * Only a `400` is the route rejecting the *request* it was given, which is the status a retired
     * `algorithm` arrives as; naming the algorithm on a 404 or a 451 would blame it for something
     * it did not cause.
     */
    private fun rejectionMessage(statusCode: Int): String =
        if (statusCode == HTTP_BAD_REQUEST) {
            "ListenBrainz Labs rejected the similar-artists request with HTTP 400: algorithm " +
                "$SIMILAR_ARTISTS_ALGORITHM has been retired, or the artist MBID was malformed"
        } else {
            "ListenBrainz Labs refused the similar-artists request with HTTP $statusCode"
        }

    private fun parseSimilarArtists(jsonArray: JSONArray): List<ListenBrainzSimilarArtist> {
        val results = mutableListOf<ListenBrainzSimilarArtist>()
        for (i in 0 until jsonArray.length()) {
            parseSimilarArtist(jsonArray.getJSONObject(i))?.let { results += it }
        }
        return results
    }

    /** Null for a row carrying no artist MBID or no name: neither can be named nor merged. */
    private fun parseSimilarArtist(item: JSONObject): ListenBrainzSimilarArtist? {
        val mbid = item.optString("artist_mbid").takeIf { it.isNotBlank() } ?: return null
        val name = item.optString("name").takeIf { it.isNotBlank() } ?: return null
        return ListenBrainzSimilarArtist(artistMbid = mbid, name = name, score = item.optInt("score", 0))
    }

    /** GET /1/explore/lb-radio?prompt=artist:({prompt})&mode={mode}. Requires authToken. */
    suspend fun getRadio(
        artistPrompt: String,
        mode: String,
    ): List<ListenBrainzRadioTrack> {
        val token = authToken ?: return emptyList()
        return rateLimiter.execute {
            val encoded = encodeQueryValue("artist:($artistPrompt)")
            val url = "$BASE_URL/explore/lb-radio?prompt=$encoded&mode=${encodeQueryValue(mode)}"
            val headers = mapOf("Authorization" to "Token $token")
            val json = httpClient.fetchJsonResult(url, headers).bodyOrThrowAuthOrTransient()
                ?: return@execute emptyList()
            parseJspfPlaylist(json)
        }
    }

    private fun parseJspfPlaylist(json: JSONObject): List<ListenBrainzRadioTrack> {
        val tracks = json
            .optJSONObject("payload")
            ?.optJSONObject("jspf")
            ?.optJSONObject("playlist")
            ?.optJSONArray("track")
            ?: return emptyList()
        val results = mutableListOf<ListenBrainzRadioTrack>()
        for (i in 0 until tracks.length()) {
            parseJspfTrack(tracks.getJSONObject(i))?.let { results += it }
        }
        return results
    }

    private fun parseJspfTrack(track: JSONObject): ListenBrainzRadioTrack? {
        val title = track.optString("title").takeIf { it.isNotBlank() } ?: return null
        val creator = track.optString("creator").takeIf { it.isNotBlank() } ?: return null
        val extension = track.optJSONObject("extension")
            ?.optJSONObject("https://musicbrainz.org/doc/jspf#playlist")
        return ListenBrainzRadioTrack(
            title = title,
            artist = creator,
            album = track.optString("album").takeIf { it.isNotBlank() },
            durationMs = track.optLong("duration", 0L).takeIf { it > 0 },
            recordingMbid = extractMbid(track.optJSONArray("identifier")?.optString(0)),
            artistMbid = extractMbid(
                extension?.optJSONArray("artist_identifiers")?.optString(0)
            ),
            releaseMbid = extractMbid(extension?.optString("release_identifier")),
        )
    }

    /** Extracts UUID from MusicBrainz URL: https://musicbrainz.org/{type}/{uuid} -> uuid */
    private fun extractMbid(url: String?): String? =
        url?.takeIf { it.contains("musicbrainz.org/") }
            ?.substringAfterLast("/")
            ?.takeIf { it.isNotBlank() }

    private fun parseTopReleaseGroups(
        jsonArray: JSONArray,
    ): List<ListenBrainzTopReleaseGroup> =
        (0 until jsonArray.length()).mapNotNull { i -> toTopReleaseGroup(jsonArray.getJSONObject(i)) }

    /** Null for an item with no release-group MBID or no name in either shape: neither can be shown. */
    private fun toTopReleaseGroup(item: JSONObject): ListenBrainzTopReleaseGroup? {
        val mbid = item.optString("release_group_mbid").takeIf { it.isNotBlank() } ?: return null
        val name = nestedName(item, "release_group")
            ?: item.optString("release_group_name").takeIf { it.isNotBlank() }
            ?: return null
        return ListenBrainzTopReleaseGroup(
            releaseGroupMbid = mbid,
            releaseGroupName = name,
            artistName = nestedArtistName(item) ?: item.optString("artist_name", ""),
            listenCount = item.optLong("total_listen_count", 0L),
        )
    }

    /**
     * The name inside [item]'s [key] object, or `null` where the object or its name is absent.
     * The flat `{key}_name` sibling this route used to send stays the fallback at the call site.
     */
    private fun nestedName(item: JSONObject, key: String): String? =
        item.optJSONObject(key)?.optString("name")?.takeIf { it.isNotBlank() }

    /** The credited artist's name, which arrives as the first entry of `artist.artists`. */
    private fun nestedArtistName(item: JSONObject): String? =
        item.optJSONObject("artist")
            ?.optJSONArray("artists")
            ?.optJSONObject(0)
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }

    companion object {
        private const val HTTP_BAD_REQUEST = 400

        const val BASE_URL = "https://api.listenbrainz.org/1"

        /**
         * The experimental host, which is a different deployment from [BASE_URL] with its own
         * uptime and its own idea of what it accepts.
         */
        const val LABS_BASE_URL = "https://labs.api.listenbrainz.org"

        /**
         * The one member of the route's `algorithm` enum this library asks for.
         *
         * The enum is the route's whole contract and is published nowhere but its own 400 body, so
         * a member can leave it without notice. Pinning one member is what makes that visible:
         * every similar-artists request carries this string, and the day the route stops accepting
         * it every request fails loudly rather than one silently returning a different ranking.
         * Session-based over 7500 days with a contribution floor of 5 — the widest window the enum
         * offers at the full 100-result limit, which is what keeps a long-tail artist answerable.
         */
        const val SIMILAR_ARTISTS_ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"

        /** The URL [getTopRecordingsForArtist] requests. */
        fun topRecordingsUrl(artistMbid: String): String =
            "$BASE_URL/popularity/top-recordings-for-artist/${encodePathSegment(artistMbid)}"

        /**
         * The URL [getSimilarArtists] requests. `artist_mbids` is plural and takes a list; this
         * library asks about one artist at a time.
         */
        fun similarArtistsUrl(artistMbid: String): String =
            "$LABS_BASE_URL/similar-artists/json" +
                "?artist_mbids=${encodeQueryValue(artistMbid)}&algorithm=$SIMILAR_ARTISTS_ALGORITHM"

        /** The URL [getTopReleaseGroupsForArtist] requests. */
        fun topReleaseGroupsUrl(artistMbid: String): String =
            "$BASE_URL/popularity/top-release-groups-for-artist/${encodePathSegment(artistMbid)}"

        /**
         * Schema-pin targets, each mirroring the parse beside it. A top-recordings row without
         * `recording_mbid` is dropped outright, so a rename there empties every popularity answer
         * silently; a similar-artists row without `artist_mbid` or `name` is dropped the same way,
         * as is a top-release-groups row without `release_group_mbid` or a name.
         *
         * Radiohead's artist MBID in the first two, chosen because the artist is large enough that
         * either endpoint answers with a full row rather than a sparse one. The third names the
         * artist its captured pool was taken from, so pin and pool warrant the same field names.
         *
         * The top-release-groups pin names `release_group.name` rather than the flat
         * `release_group_name` that [toTopReleaseGroup] still falls back to: the flat sibling is
         * absent from the live answer, so pinning it would report drift on every healthy run. Only
         * the two fields [ListenBrainzMapper.toDiscography] reads are pinned — `total_listen_count`
         * and the credited artist name are parsed but reach no enrichment answer, so their absence
         * is not drift.
         *
         * A retired `algorithm` is *not* what these catch — the pin reports any non-200 as
         * unavailable rather than as drift, and [getSimilarArtists] is what turns that case into a
         * provider error a consumer sees.
         */
        val SCHEMA_PIN_TARGETS: List<SchemaTarget> = listOf(
            SchemaTarget(
                provider = "listenbrainz",
                route = "top recordings for artist",
                url = topRecordingsUrl("a74b1b7f-71a5-4011-9441-d0b5e4122711"),
                shape = BodyShape.ARRAY,
                requiredPaths = listOf(
                    "[0].recording_mbid",
                    "[0].recording_name",
                    "[0].artist_name",
                    "[0].total_listen_count",
                    "[0].length",
                    "[0].release_name",
                ),
            ),
            SchemaTarget(
                provider = "listenbrainz",
                route = "labs similar artists",
                url = similarArtistsUrl("a74b1b7f-71a5-4011-9441-d0b5e4122711"),
                shape = BodyShape.ARRAY,
                requiredPaths = listOf(
                    "[0].artist_mbid",
                    "[0].name",
                    "[0].score",
                ),
            ),
            SchemaTarget(
                provider = "listenbrainz",
                route = "top release groups for artist",
                url = topReleaseGroupsUrl("ac9a487a-d9d2-4f27-bb23-0f4686488345"),
                shape = BodyShape.ARRAY,
                requiredPaths = listOf(
                    "[0].release_group_mbid",
                    "[0].release_group.name",
                ),
            ),
        )
    }
}

/**
 * The Labs host answered, and refused the request. Thrown rather than collapsed to an empty list so
 * that `mapError` turns it into an `EnrichmentResult.Error`: a route that answers "nothing similar"
 * with `200 []` leaves a 4xx no reading other than "this request is no longer valid".
 */
internal class LabsRequestRejectedException(message: String) : IllegalStateException(message)
