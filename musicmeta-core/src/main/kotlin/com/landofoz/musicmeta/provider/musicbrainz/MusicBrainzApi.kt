package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import java.net.URLEncoder
import org.json.JSONObject

/**
 * MusicBrainz API client. Handles query building, rate limiting, and parsing.
 * All requests use Lucene query syntax with JSON responses.
 */
class MusicBrainzApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchReleases(
        title: String,
        artist: String,
        limit: Int = 5,
    ): List<MusicBrainzRelease> {
        val query = buildQuery("release", title, "artistname", artist)
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult("$BASE_URL/release?query=$query&fmt=json&limit=$limit")) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return MusicBrainzParser.parseReleases(json)
    }

    suspend fun searchArtists(
        name: String,
        limit: Int = 5,
    ): List<MusicBrainzArtist> {
        val query = encode("artist:\"${escapeLucene(name)}\"")
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult("$BASE_URL/artist?query=$query&fmt=json&limit=$limit")) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return MusicBrainzParser.parseArtists(json)
    }

    suspend fun searchRecordings(
        title: String,
        artist: String,
        limit: Int = 5,
    ): List<MusicBrainzRecording> {
        val query = buildQuery("recording", title, "artistname", artist)
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult("$BASE_URL/recording?query=$query&fmt=json&limit=$limit")) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return MusicBrainzParser.parseRecordings(json)
    }

    suspend fun lookupRelease(mbid: String): MusicBrainzRelease? {
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(
                "$BASE_URL/release/$mbid?fmt=json" +
                    "&inc=artist-credits+labels+release-groups+tags+media+recordings",
            )) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return MusicBrainzParser.parseLookupRelease(json)
    }

    suspend fun lookupArtist(mbid: String): MusicBrainzArtist? {
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult("$BASE_URL/artist/$mbid?fmt=json&inc=tags+url-rels")) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return MusicBrainzParser.parseLookupArtist(json)
    }

    /** Lookup artist with artist-rels included (needed for band member relationships). */
    suspend fun lookupArtistWithRels(mbid: String): MusicBrainzArtist? {
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult("$BASE_URL/artist/$mbid?fmt=json&inc=tags+url-rels+artist-rels")) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return MusicBrainzParser.parseLookupArtist(json)
    }

    /** Lookup a recording by MBID with artist-rels and work-rels (needed for credits). */
    suspend fun lookupRecording(mbid: String): JSONObject? {
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(
                "$BASE_URL/recording/$mbid?fmt=json&inc=artist-rels+work-rels",
            )) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        }
        return json
    }

    /** Browse release groups for an artist (for discography). */
    suspend fun browseReleaseGroups(
        artistMbid: String,
        limit: Int = 100,
    ): List<MusicBrainzReleaseGroup> {
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(
                "$BASE_URL/release-group?artist=$artistMbid" +
                    "&type=album|ep|single&fmt=json&limit=$limit",
            )) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return MusicBrainzParser.parseReleaseGroups(json)
    }

    /** Build a Lucene query with two fields and URL-encode the ENTIRE thing. */
    private fun buildQuery(field1: String, value1: String, field2: String, value2: String): String =
        encode("$field1:\"${escapeLucene(value1)}\" AND $field2:\"${escapeLucene(value2)}\"")

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val BASE_URL = "https://musicbrainz.org/ws/2"
        private val LUCENE_SPECIAL_CHARS = """[+\-&|!()\{}\[\]^"~*?:\\/]""".toRegex()

        fun escapeLucene(value: String): String =
            value.replace(LUCENE_SPECIAL_CHARS) { "\\${it.value}" }
    }
}
