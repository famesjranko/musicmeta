package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import org.json.JSONObject
import java.net.URLEncoder

/**
 * MusicBrainz API client. Handles query building, rate limiting, and parsing.
 * All requests use Lucene query syntax with JSON responses.
 */
internal class MusicBrainzApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchReleases(
        title: String,
        artist: String,
        limit: Int = RELEASE_SEARCH_LIMIT,
    ): List<MusicBrainzRelease> {
        val query = buildQuery("release", title, "artistname", artist)
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/release?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseReleases(json)
    }

    /** Broader fuzzy search (unquoted + Lucene ~) for near-miss suggestions. */
    suspend fun searchReleasesFuzzy(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<MusicBrainzRelease> {
        val query = encode("release:${escapeLucene(title)}~ AND artistname:${escapeLucene(artist)}~")
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/release?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseReleases(json)
    }

    suspend fun searchArtists(
        name: String,
        limit: Int = 5,
    ): List<MusicBrainzArtist> {
        val query = encode("artist:\"${escapeLucene(name)}\"")
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/artist?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseArtists(json)
    }

    /** Broader fuzzy search (unquoted + Lucene ~) for near-miss suggestions. */
    suspend fun searchArtistsFuzzy(
        name: String,
        limit: Int = 3,
    ): List<MusicBrainzArtist> {
        val query = encode("artist:${escapeLucene(name)}~")
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/artist?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseArtists(json)
    }

    /**
     * Finds recordings matching [title]/[artist], optionally narrowed by [album].
     *
     * Same disease as [searchArtists]/[searchReleases] (`docs/pitfalls.md` §7): MB's recording
     * search ties score-100 hits and its own ordering is not trustworthy — a demo or live take can
     * sort ahead of the studio original. Adding a `release:"…"` term when [album] is known asks MB
     * to filter by album up front, the same shape as the two-field query [buildQuery] already
     * builds. Album titles drift across editions, so a hinted query that comes back empty falls
     * back to the hint-less query rather than reporting no match. Ranking the returned pool (rather
     * than trusting hit 0) is the caller's job — [MusicBrainzEnricher] has the score threshold and
     * the disambiguation/release-type signals to do it. [limit] defaults to
     * [RECORDING_SEARCH_LIMIT], wide enough for the studio original to still be in the pool when
     * several score-100 live/bootleg/cover takes sort ahead of it.
     */
    suspend fun searchRecordings(
        title: String,
        artist: String,
        album: String? = null,
        limit: Int = RECORDING_SEARCH_LIMIT,
    ): List<MusicBrainzRecording> {
        val albumHint = album?.takeIf { it.isNotBlank() }
        val hinted = albumHint?.let { fetchRecordings(recordingQuery(title, artist, it), limit, it) }
        return hinted?.takeIf { it.isNotEmpty() }
            ?: fetchRecordings(recordingQuery(title, artist, null), limit, albumHint)
    }

    /**
     * [albumHint] is always the request's own album (independent of whether [query] itself carries
     * a `release:` term) — even the hint-less fallback query wants it, so [MusicBrainzParser]'s
     * album-match tier can still prefer the right release-group among whatever this query returns.
     */
    private suspend fun fetchRecordings(
        query: String,
        limit: Int,
        albumHint: String? = null,
    ): List<MusicBrainzRecording> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/recording?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseRecordings(json, albumHint)
    }

    /**
     * Broader fuzzy search (unquoted + Lucene `~`) for near-miss suggestions — same shape as
     * [searchReleasesFuzzy]/[searchArtistsFuzzy]. [searchRecordings]'s own hint-less retry only
     * drops the `release:"…"` album term; it re-sends [title] quoted, so it can never rescue a typo
     * the way this loosened match can. Like the release/artist variants, this deliberately takes no
     * album hint — a fuzzy near-miss query is already loose, and a release term would narrow it.
     */
    suspend fun searchRecordingsFuzzy(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<MusicBrainzRecording> {
        val query = encode("recording:${escapeLucene(title)}~ AND artistname:${escapeLucene(artist)}~")
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/recording?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseRecordings(json)
    }

    /** Lucene query for a recording search, with an optional `release:"…"` term when [album] is known. */
    private fun recordingQuery(title: String, artist: String, album: String?): String {
        val base = "recording:\"${escapeLucene(title)}\" AND artistname:\"${escapeLucene(artist)}\""
        val withAlbum = if (album.isNullOrBlank()) base else "$base AND release:\"${escapeLucene(album)}\""
        return encode(withAlbum)
    }

    suspend fun lookupRelease(mbid: String): MusicBrainzRelease? {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release/$mbid?fmt=json" +
                    "&inc=artist-credits+labels+release-groups+tags+media+recordings",
            ).bodyOrThrowTransient()
        } ?: return null
        return MusicBrainzParser.parseLookupRelease(json)
    }

    suspend fun lookupArtist(mbid: String): MusicBrainzArtist? {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/artist/$mbid?fmt=json&inc=tags+url-rels").bodyOrThrowTransient()
        } ?: return null
        return MusicBrainzParser.parseLookupArtist(json)
    }

    /** Lookup artist with artist-rels included (needed for band member relationships). */
    suspend fun lookupArtistWithRels(mbid: String): MusicBrainzArtist? {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/artist/$mbid?fmt=json&inc=tags+url-rels+artist-rels")
                .bodyOrThrowTransient()
        } ?: return null
        return MusicBrainzParser.parseLookupArtist(json)
    }

    /** Lookup a release-group by MBID with releases (needed for editions). */
    suspend fun lookupReleaseGroup(releaseGroupMbid: String): JSONObject? {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release-group/$releaseGroupMbid?fmt=json&inc=releases",
            ).bodyOrThrowTransient()
        }
        return json
    }

    /**
     * Wikidata/Wikipedia URL relations for a release-group, needed for `ALBUM_DESCRIPTION`
     * (`Wikipedia`'s title/wikidata resolution) — a separate lookup because a release-group's own
     * relations are never embedded in a release search or release lookup response.
     */
    suspend fun lookupReleaseGroupWikiLinks(releaseGroupMbid: String): Pair<String?, String?> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release-group/$releaseGroupMbid?fmt=json&inc=url-rels",
            ).bodyOrThrowTransient()
        } ?: return null to null
        return MusicBrainzParser.parseReleaseGroupWikiLinks(json)
    }

    /**
     * Lookup a recording by MBID, at the union of what every track type reads: `artist-rels` and
     * `work-rels` for CREDITS, the rest for the recording itself. Returned raw because the two
     * readers parse it differently — [MusicBrainzCreditParser.parseRecordingCredits] and
     * [MusicBrainzParser.parseLookupRecording] — off one response and so one upstream request.
     *
     * `release-groups` is not optional alongside `releases`: without it MusicBrainz returns releases
     * carrying no `release-group` object at all, which [MusicBrainzParser] reads as "no art"
     * (verified live 2026-08-10).
     */
    suspend fun lookupRecording(mbid: String): JSONObject? {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/recording/$mbid?fmt=json&inc=$RECORDING_LOOKUP_INC",
            ).bodyOrThrowTransient()
        }
        return json
    }

    /**
     * Browse release groups for an artist (for discography). MusicBrainz does not order the browse
     * by anything a caller can rely on, so a catalogue past [limit] needs [offset] to reach its tail.
     */
    suspend fun browseReleaseGroups(
        artistMbid: String,
        limit: Int = BROWSE_PAGE_SIZE,
        offset: Int = 0,
    ): List<MusicBrainzReleaseGroup> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release-group?artist=$artistMbid" +
                    "&type=album|ep|single&fmt=json&limit=$limit&offset=$offset",
            ).bodyOrThrowTransient()
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

        /**
         * Default candidate pool size for [searchRecordings]. A well-covered track's score-100
         * ties can run deep in live/bootleg/cover takes — "Enter Sandman"/Metallica has ~750 tied
         * recordings, and measured live (2026-08-06) `limit=5`, and in some runs even `limit=15`,
         * returned *no* clean-disambiguation studio hit at all, so
         * [MusicBrainzEnricher.pickBestRecording] had nothing to rank. Where the studio hit lands
         * inside the pool is not stable across requests (MB's tie order shifts with the limit
         * itself), so the value is headroom, not a measured index: 25 reliably contained several
         * studio candidates in repeated runs. A pathologically over-tied track could still
         * overflow it.
         */
        const val RECORDING_SEARCH_LIMIT = 25

        /**
         * Default candidate pool size for [searchReleases]. Same disease as [RECORDING_SEARCH_LIMIT].
         * Measured over 30 albums: at `limit=5` the release the ranking would pick was absent from
         * the pool on 22 of them, and mean drift from the release group's first-release-date fell
         * from 6.8 years to 0.1 at `limit=25`.
         */
        const val RELEASE_SEARCH_LIMIT = 25

        /** Release groups per [browseReleaseGroups] page — MusicBrainz's own maximum. */
        const val BROWSE_PAGE_SIZE = 100

        /** [lookupRecording]'s `inc=`; see its KDoc for why each half is there. */
        private const val RECORDING_LOOKUP_INC =
            "artist-rels+work-rels+artists+releases+release-groups+isrcs+tags"

        fun escapeLucene(value: String): String =
            value.replace(LUCENE_SPECIAL_CHARS) { "\\${it.value}" }
    }
}
