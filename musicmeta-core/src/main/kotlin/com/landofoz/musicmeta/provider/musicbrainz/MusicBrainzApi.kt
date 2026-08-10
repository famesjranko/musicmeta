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
     * the disambiguation/release-type signals to do it.
     *
     * This is the pool a consumer *chooses* from, so it stays unfiltered: a "did you mean?" list
     * narrowed to canonical recordings cannot answer "I want the Moscow one".
     * [searchCanonicalRecordings] is what a track is resolved out of.
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
     * The pool a track request is *resolved* out of, as opposed to the one
     * [MusicBrainzProvider.searchCandidates] offers a consumer to choose from.
     *
     * `-comment:*` is tier 4 of [MusicBrainzEnricher.pickBestRecording] — prefer a blank
     * disambiguation — expressed in MusicBrainz's own query language, which is what moves it
     * *upstream* of the limit instead of downstream of it. Downstream it can only rank what the
     * limit already let through, and for a heavily-covered track that is nothing: measured
     * 2026-08-10, zero of the 25 recordings the unfiltered query returns for "Enter Sandman" carry
     * a blank disambiguation, so the tier had nothing to choose and the ranking fell through to
     * whichever live or demo take won on the tiers below.
     *
     * **Only when the request names no album.** An album is the better narrowing term and the one
     * the caller supplied, and the two do not compose: the filter deletes candidates, so a canonical
     * recording that carries a disambiguation *and* sits on the requested album would be gone before
     * [MusicBrainzEnricher.pickBestRecording]'s album-match tier — which outranks its
     * disambiguation tier precisely because an explicit album is the stronger signal — could prefer
     * it. A hinted request is not the failing case anyway: measured 2026-08-10, adding the album
     * term took "Enter Sandman" from 757 candidates to 45 and "Paranoid Android" to 6. So a hinted
     * request takes [searchRecordings] unchanged.
     *
     * A filter can only remove candidates, so an empty filtered pool falls back to the unfiltered
     * ladder — a track whose every recording is marked must still resolve. That costs one extra
     * request, on the miss path only.
     *
     * What that fallback does *not* rescue is a track whose canonical recording is marked while
     * other takes are not: the right answer is removed and the pool is still non-empty, so nothing
     * fires. Measured over six tracks on 2026-08-10, five kept an unmarked recording on the
     * requested album's release-group (at indices 1, 25, 34, 56 and 58 — four of them past the 25
     * an unfiltered search would have returned), and one, Prince's "Purple Rain", kept none. That
     * one is no worse than before rather than newly broken: the unfiltered pool holds no such
     * recording in its first 25 either, and the ranking's own disambiguation tier would have
     * preferred the same unmarked non-album takes. It is not fixed here.
     */
    suspend fun searchCanonicalRecordings(
        title: String,
        artist: String,
        album: String? = null,
    ): List<MusicBrainzRecording> {
        if (!album.isNullOrBlank()) return searchRecordings(title, artist, album)
        val canonical = fetchRecordings(
            recordingQuery(title, artist, null, canonicalOnly = true),
            CANONICAL_SEARCH_LIMIT,
        )
        return canonical.ifEmpty { searchRecordings(title, artist, null) }
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

    /**
     * Lucene query for a recording search, with an optional `release:"…"` term when [album] is
     * known and, for [canonicalOnly], the `-comment:*` term that excludes every recording carrying
     * a disambiguation. Both terms narrow; neither reorders.
     */
    private fun recordingQuery(
        title: String,
        artist: String,
        album: String?,
        canonicalOnly: Boolean = false,
    ): String {
        val base = "recording:\"${escapeLucene(title)}\" AND artistname:\"${escapeLucene(artist)}\""
        val withAlbum = if (album.isNullOrBlank()) base else "$base AND release:\"${escapeLucene(album)}\""
        return encode(if (canonicalOnly) "$withAlbum AND -comment:*" else withAlbum)
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
         * Default candidate pool size for [searchRecordings] — the *candidate* surface, where a
         * consumer picks a version, and the fallback when [searchCanonicalRecordings] filters the
         * pool to nothing. It is not what a track is resolved out of, and it deliberately carries
         * no claim about containing a studio candidate: it did not hold one for
         * "Enter Sandman"/Metallica when measured on 2026-08-10
         * (`scripts/probes/recording-pool-filter-probe.sh`, 757 tied recordings, 0 of the returned
         * 25 with a blank disambiguation), which is the defect [searchCanonicalRecordings] exists
         * to fix. Raising it would not have fixed it: a wider unfiltered window is still ordered by
         * a relevance score every one of those 757 ties shares.
         */
        const val RECORDING_SEARCH_LIMIT = 25

        /**
         * Pool size for [searchCanonicalRecordings], and **MusicBrainz's own maximum** — not
         * headroom, and not a number to raise. Above 100 the search does not clamp; it silently
         * serves the default 25 (`limit=115` → `returned=25`, measured 2026-08-10), so a later
         * increase would shrink the pool without failing.
         *
         * It is a ceiling rather than a bound the filter guarantees. Measured over four tracks the
         * same day, the filtered pool ran 71–132: "Enter Sandman" 95 and "Comfortably Numb" 71 fit,
         * "Paranoid Android" 115 and "Whipping Post" 132 did not. Where the canonical cut lands
         * inside the pool is upstream's to decide and shifts between identical calls — indices 19,
         * 23, 39 and 58 across four runs of the same query — so this buys a pool the ranking can
         * work on, not a guarantee that the studio take is in it. Reaching past 100 means paging,
         * and costs a request per page on a 1 req/s limiter.
         */
        const val CANONICAL_SEARCH_LIMIT = 100

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
