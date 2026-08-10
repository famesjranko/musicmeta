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
     * *upstream* of the limit instead of downstream of it. Downstream the tier can only rank what
     * the page already let through, and for a heavily-covered track that is nothing at all.
     *
     * The filter only ever removes candidates, so the whole ladder here is about the requests where
     * the one it removes is the answer:
     *
     * - **[title] itself ends in a bracketed group** — the request names the variant it wants, and
     *   the filter deletes precisely that recording while leaving the pool full, so no fallback
     *   fires and the unmarked studio take wins instead. Verified live 2026-08-10: U2's "Where the
     *   Streets Have No Name (live at Rotterdam)" carries the variant in its title *and* in its
     *   disambiguation, and that title queried with `-comment:*` returns count 0 — MusicBrainz
     *   keeping variant text out of the title is the common case, not a rule. Such a request takes
     *   the unfiltered [searchRecordings] ladder whole. The test is structural rather than a
     *   vocabulary of variant words (`docs/pitfalls.md` §7), so a canonical title that merely ends
     *   in brackets ("Sgt. Pepper's Lonely Hearts Club Band (Reprise)") takes that ladder too. That
     *   is the safe direction: it degrades to the pool that shipped before the filter existed,
     *   never to a different recording.
     * - **an album hint that finds nothing** — [recordingQuery]'s `release:"…"` term matches release
     *   (edition) titles, while [MusicBrainzEnricher.pickBestRecording] matches on
     *   [MusicBrainzRecording.artReleaseGroupTitle], the release *group* title. The two genuinely
     *   diverge, so an empty hinted pool is not evidence the album is absent, and the hint-less
     *   retry has to serve both readings: the filtered pool at [CANONICAL_SEARCH_LIMIT] for the
     *   depth this function exists for, and the unfiltered one at [RECORDING_SEARCH_LIMIT] to keep
     *   a recording that is marked *and* sits on the requested album reachable — the album-match
     *   tier outranks the disambiguation tier, so the filter would delete a candidate the ranking
     *   would have preferred. Their union, deduplicated by recording id, is that pool. One extra
     *   request, on the miss path only.
     * - **an empty filtered pool** on a hint-less request — a track whose every recording is marked
     *   must still resolve, so the unfiltered query follows. Also one extra request, on that path
     *   only. It does not rescue a track whose canonical recording is marked while other takes are
     *   not: the right answer is removed, the pool is not empty, and nothing fires. That case is no
     *   worse than before rather than newly broken, and is not fixed here.
     *
     * A hinted query that does find recordings answers alone and unfiltered. The two do not compose
     * — the filter would delete the marked album take the hint was asking for — and an album is the
     * better narrowing term on its own.
     *
     * `scripts/probes/recording-pool-filter-probe.sh` measures the pools; the figures that bound the
     * design live on [CANONICAL_SEARCH_LIMIT], and nowhere else.
     */
    suspend fun searchCanonicalRecordings(
        title: String,
        artist: String,
        album: String? = null,
    ): List<MusicBrainzRecording> {
        if (MusicBrainzQualifierFallback.hasTrailingGroup(title)) return searchRecordings(title, artist, album)
        val albumHint = album?.takeIf { it.isNotBlank() }
        if (albumHint == null) return canonicalPool(title, artist).ifEmpty { shallowPool(title, artist, null) }
        val hinted = fetchRecordings(recordingQuery(title, artist, albumHint), RECORDING_SEARCH_LIMIT, albumHint)
        return hinted.ifEmpty {
            (canonicalPool(title, artist, albumHint) + shallowPool(title, artist, albumHint)).distinctBy { it.id }
        }
    }

    /** The hint-less filtered pool, at the deep page — what buys the depth the filter is for. */
    private suspend fun canonicalPool(
        title: String,
        artist: String,
        albumHint: String? = null,
    ): List<MusicBrainzRecording> =
        fetchRecordings(recordingQuery(title, artist, null, canonicalOnly = true), CANONICAL_SEARCH_LIMIT, albumHint)

    /** The hint-less unfiltered pool, at the shallow page — the shape that shipped before the filter. */
    private suspend fun shallowPool(
        title: String,
        artist: String,
        albumHint: String?,
    ): List<MusicBrainzRecording> =
        fetchRecordings(recordingQuery(title, artist, null), RECORDING_SEARCH_LIMIT, albumHint)

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

    suspend fun lookupRelease(mbid: String): MusicBrainzLookup<MusicBrainzRelease> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release/$mbid?fmt=json" +
                    "&inc=artist-credits+labels+release-groups+tags+media+recordings",
            ).bodyOrThrowTransient()
        } ?: return MusicBrainzLookup.Absent
        val release = MusicBrainzParser.parseLookupRelease(json) ?: return MusicBrainzLookup.Unreadable
        return MusicBrainzLookup.Found(release)
    }

    /** Lookup artist with artist-rels included (needed for band member relationships). */
    suspend fun lookupArtistWithRels(mbid: String): MusicBrainzLookup<MusicBrainzArtist> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/artist/$mbid?fmt=json&inc=tags+url-rels+artist-rels")
                .bodyOrThrowTransient()
        } ?: return MusicBrainzLookup.Absent
        val artist = MusicBrainzParser.parseLookupArtist(json) ?: return MusicBrainzLookup.Unreadable
        return MusicBrainzLookup.Found(artist)
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
     *
     * Never [MusicBrainzLookup.Unreadable] — this one does not parse, so whether the body reads is
     * its two readers' answer to give, not this function's.
     */
    suspend fun lookupRecording(mbid: String): MusicBrainzLookup<JSONObject> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/recording/$mbid?fmt=json&inc=$RECORDING_LOOKUP_INC",
            ).bodyOrThrowTransient()
        } ?: return MusicBrainzLookup.Absent
        return MusicBrainzLookup.Found(json)
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
         * pool to nothing. It is not what a track is resolved out of, and it deliberately carries no
         * claim about containing a studio candidate — it did not hold one for a heavily-covered
         * track when measured (`scripts/probes/recording-pool-filter-probe.sh`), which is the defect
         * [searchCanonicalRecordings] exists to fix. Raising it would not have fixed it: a wider
         * unfiltered window is still ordered by a relevance score every tied candidate shares.
         */
        const val RECORDING_SEARCH_LIMIT = 25

        /**
         * Pool size for [searchCanonicalRecordings], and **MusicBrainz's own maximum** — not
         * headroom, and not a number to raise. Above 100 the search does not clamp; it silently
         * serves the default 25 (`limit=115` → `returned=25`, measured 2026-08-10), so a later
         * increase would shrink the pool without failing.
         *
         * It is a ceiling rather than a bound the filter guarantees, and the overflow is not a
         * long-tail case — it lands on the titles most likely to be asked for. Measured 2026-08-10
         * over seven tracks, filtered pools ran 71–192 and three exceeded 100. Where a given
         * recording sits inside a pool is upstream's to decide and shifts between identical calls.
         * So this buys a pool the ranking can work on, not a guarantee the studio take is in it.
         * Reaching past 100 means paging, at a request per page on a 1 req/s limiter.
         *
         * Re-measure with `scripts/probes/recording-pool-filter-probe.sh` before relying on any of
         * those figures; they decay as MusicBrainz's catalogue grows. This KDoc is their one home —
         * `docs/providers.md` and the probe state the shape and point here rather than restating
         * them, because the copies drifted apart within a day of being written.
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
