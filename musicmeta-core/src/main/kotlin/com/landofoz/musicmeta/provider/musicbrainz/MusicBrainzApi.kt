package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.drift.SchemaTarget
import com.landofoz.musicmeta.engine.CallMemo
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.provider.encodePathSegment
import com.landofoz.musicmeta.provider.encodeQueryValue
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

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
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(releaseSearchUrl(title, artist, limit)).bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseReleases(json)
    }

    /** Broader fuzzy search (unquoted + Lucene ~) for near-miss suggestions. */
    suspend fun searchReleasesFuzzy(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<MusicBrainzRelease> {
        val query = encodeQueryValue("release:${escapeLucene(title)}~ AND artistname:${escapeLucene(artist)}~")
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/release?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseReleases(json)
    }

    /**
     * The artist candidate pool for [name], searched by name **and** by alias.
     *
     * `artist:"…"` alone does not reach aliases — verified live 2026-08-12: Coldplay's documented
     * alias "Cold Play" returns `count: 0` under `artist:"Cold Play"` and `count: 1` under
     * `alias:"Cold Play"`. Only an unfielded query matches both, and unfielded is far too loose (the
     * same probe returned 4152 hits). The `OR` keeps one request and leaves a canonical hit ranked
     * where it was: MusicBrainz still scores the exact-name match 100 and returns it first.
     *
     * Every hit carries its own `aliases` array without an `inc=` parameter, which is what lets
     * [MusicBrainzEnricher] score *how* a name matched rather than merely that it did.
     */
    suspend fun searchArtists(
        name: String,
        limit: Int = 5,
    ): List<MusicBrainzArtist> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(artistSearchUrl(name, limit)).bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseArtists(json)
    }

    /** Broader fuzzy search (unquoted + Lucene ~) for near-miss suggestions. */
    suspend fun searchArtistsFuzzy(
        name: String,
        limit: Int = 3,
    ): List<MusicBrainzArtist> {
        val query = encodeQueryValue("artist:${escapeLucene(name)}~")
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
            ?: plainRecordingPool(title, artist, limit, albumHint)
    }

    /**
     * The pool a track request is *resolved* out of, as opposed to the one
     * [MusicBrainzProvider.searchCandidates] offers a consumer to choose from.
     *
     * `-comment:*` moves [MusicBrainzTrackEnrichment.pickBestRecording]'s tier-4 "prefer a blank
     * disambiguation" rule upstream of the result limit, in MusicBrainz's own query language, so a
     * heavily-covered track's studio take is not paged out before that tier can rank it
     * (`docs/pitfalls.md` §7). Three cases keep the unfiltered pool in reach because the filter
     * would otherwise remove the correct answer: [title] itself ends in a bracketed group
     * ([MusicBrainzQualifierFallback.hasTrailingGroup] — the shape, not a vocabulary), where the
     * named recording may carry the disambiguation the filter deletes, so both hint-less pools are
     * unioned rather than trusting either alone — a bracketed-but-canonical title ("(Reprise)",
     * "(feat. X)") keeps the deep page, and a genuinely-variant one stays reachable through the
     * unfiltered arm; an album hint finds nothing, since [recordingQuery]'s `release:"…"` (edition
     * title) and [MusicBrainzRecording.artReleaseGroupTitle] (release-*group* title) can genuinely
     * diverge, so the hint-less retry unions the same two pools instead of trusting the empty
     * hinted one; and a hint-less filtered pool comes back empty because every candidate recording
     * is marked. A hinted query that does find recordings answers alone and unfiltered, since the
     * filter would delete the marked album take the hint was asking for.
     *
     * `scripts/probes/recording-pool-filter-probe.sh` measures the pools; the figures that bound the
     * design live on [CANONICAL_SEARCH_LIMIT], and nowhere else.
     */
    suspend fun searchCanonicalRecordings(
        title: String,
        artist: String,
        album: String? = null,
    ): List<MusicBrainzRecording> {
        val albumHint = album?.takeIf { it.isNotBlank() }
        if (albumHint == null) {
            return if (MusicBrainzQualifierFallback.hasTrailingGroup(title)) {
                (canonicalPool(title, artist) + shallowPool(title, artist, null)).distinctBy { it.id }
            } else {
                canonicalPool(title, artist).ifEmpty { shallowPool(title, artist, null) }
            }
        }
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

    /**
     * The hint-less unfiltered pool, at the shallow page — the shape that shipped before the filter.
     * Byte-identical to [searchRecordings]'s own hint-less fallback query, so both go through
     * [plainRecordingPool] rather than fetching it twice.
     */
    private suspend fun shallowPool(
        title: String,
        artist: String,
        albumHint: String?,
    ): List<MusicBrainzRecording> =
        plainRecordingPool(title, artist, RECORDING_SEARCH_LIMIT, albumHint)

    /**
     * The hint-less plain recording query, shared for the life of one call
     * ([ProviderCallScope]) between [searchRecordings]'s hint-less fallback and [shallowPool] —
     * both build the identical URL whenever [title]/[artist]/[limit] agree, so MusicBrainz should
     * see it once per call, not once per caller.
     *
     * Memoized as the raw response, not [MusicBrainzParser]'s output: parsing takes [albumHint] and
     * uses it to prefer a release group per hit, so two callers with different hints must parse
     * their own copy from the one shared fetch rather than share a parse meant for the other's hint.
     * [albumHint] is therefore no part of the memo key.
     */
    private suspend fun plainRecordingPool(
        title: String,
        artist: String,
        limit: Int,
        albumHint: String?,
    ): List<MusicBrainzRecording> {
        val json = plainRecordingPoolMemo().get(PlainRecordingQuery(title, artist, limit)) {
            fetchRecordingsJson(recordingQuery(title, artist, null), limit)
        } ?: return emptyList()
        return MusicBrainzParser.parseRecordings(json, albumHint)
    }

    private data class PlainRecordingQuery(val title: String, val artist: String, val limit: Int)

    /**
     * This call's memo ([ProviderCallScope]), never this instance's — [MusicBrainzApi] is one
     * instance per [MusicBrainzProvider], so a memo held here would outlive the call that filled it
     * and could serve a `forceRefresh` the previous call's payload (`docs/pitfalls.md` §12). A
     * caller with no scope (used outside an engine) gets a fresh memo per call, the same guarantee.
     */
    private suspend fun plainRecordingPoolMemo(): CallMemo<PlainRecordingQuery, JSONObject?> =
        currentCoroutineContext()[ProviderCallScope]?.slot(this, ::CallMemo) ?: CallMemo()

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
        val json = fetchRecordingsJson(query, limit) ?: return emptyList()
        return MusicBrainzParser.parseRecordings(json, albumHint)
    }

    /** The raw upstream response for a recording search, before [MusicBrainzParser] sees it. */
    private suspend fun fetchRecordingsJson(query: String, limit: Int): JSONObject? =
        rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/recording?query=$query&fmt=json&limit=$limit").bodyOrThrowTransient()
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
        val query = encodeQueryValue("recording:${escapeLucene(title)}~ AND artistname:${escapeLucene(artist)}~")
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
        return encodeQueryValue(if (canonicalOnly) "$withAlbum AND -comment:*" else withAlbum)
    }

    suspend fun lookupRelease(mbid: String): MusicBrainzLookup<MusicBrainzRelease> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release/${encodePathSegment(mbid)}?fmt=json" +
                    // No `ratings`: MusicBrainz rates release *groups*, not releases, so a release
                    // lookup asking for them answers `"rating": null` every time (live 2026-08-12).
                    "&inc=artist-credits+labels+release-groups+tags+genres+media+recordings",
            ).bodyOrThrowTransient()
        } ?: return MusicBrainzLookup.Absent
        val release = MusicBrainzParser.parseLookupRelease(json) ?: return MusicBrainzLookup.Unreadable
        return MusicBrainzLookup.Found(release)
    }

    /**
     * Lookup artist with artist-rels included (needed for band member relationships).
     *
     * `genres` is the curated subset of `tags` and answers `GENRE` ahead of them; `aliases` lets a
     * name that reached us as an alias still be scored as one; `ratings` rides along for a
     * popularity surface that does not exist yet. None of the three costs a request.
     */
    suspend fun lookupArtistWithRels(mbid: String): MusicBrainzLookup<MusicBrainzArtist> {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult("$BASE_URL/artist/${encodePathSegment(mbid)}?fmt=json&inc=$ARTIST_LOOKUP_INC")
                .bodyOrThrowTransient()
        } ?: return MusicBrainzLookup.Absent
        val artist = MusicBrainzParser.parseLookupArtist(json) ?: return MusicBrainzLookup.Unreadable
        return MusicBrainzLookup.Found(artist)
    }

    /**
     * Browse every release in a release group by its MBID — what `RELEASE_EDITIONS` answers with.
     *
     * A browse rather than a release-group lookup because `labels` is not a valid `inc` on the
     * release-group resource, so a lookup can never carry an edition's label or catalogue number,
     * and its inline `releases` array carries no `media` either. `labels`+`media` are the three
     * fields `ReleaseEdition` promises; `artist-credits` is each release's own credit; and
     * `release-groups` is what carries the guard data — the group's `first-release-date` and its
     * own artist credit, repeated on every release, which is the only place a browse holds them.
     *
     * The cost is bytes: 21,932 for the old lookup against 54,556 for this browse, over a
     * 27-release group (measured 2026-08-25).
     *
     * `limit=100` is MusicBrainz's browse maximum and the documented bound on the answer — a group
     * with more than 100 releases is truncated, deliberately, rather than paged over.
     */
    suspend fun browseReleaseGroupReleases(releaseGroupMbid: String): JSONObject? {
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(
                "$BASE_URL/release?release-group=${encodeQueryValue(releaseGroupMbid)}&fmt=json" +
                    "&inc=$RELEASE_BROWSE_INC&limit=$RELEASE_BROWSE_LIMIT",
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
                "$BASE_URL/release-group/${encodePathSegment(releaseGroupMbid)}?fmt=json&inc=url-rels",
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
                "$BASE_URL/recording/${encodePathSegment(mbid)}?fmt=json&inc=$RECORDING_LOOKUP_INC",
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
                // The pipes reach MusicBrainz as %7C: java.net.URI rejects a raw | in a query.
                "$BASE_URL/release-group?artist=${encodeQueryValue(artistMbid)}" +
                    "&type=album%7Cep%7Csingle&fmt=json&limit=$limit&offset=$offset",
            ).bodyOrThrowTransient()
        } ?: return emptyList()
        return MusicBrainzParser.parseReleaseGroups(json)
    }

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
         * `docs/provider-internals.md` and the probe state the shape and point here rather than
         * restating them, because the copies drifted apart within a day of being written.
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
        // `work-level-rels` is not optional alongside `work-rels`, and its absence is what made
        // CREDITS lose every songwriter: `work-rels` returns the work as a stub — id and title, no
        // `relations` — so the writers MusicBrainz models on the work were never in the response at
        // all. It inlines them into this same request rather than costing a second one; a
        // /work/{mbid} lookup per recording buys the identical answer for one more round trip
        // (measured 2026-08-24, docs/pitfalls.md covers the trap).
        //
        // The cost is bytes, on every recording lookup whether or not CREDITS was asked for: 7,672
        // to 10,600 for the recording that was measured. Deliberately unconditional — varying
        // `inc=` per requested type would make two response shapes share one cache key, and serving
        // a credits-free entry to a caller who asked for credits is the more expensive bug.
        private const val RECORDING_LOOKUP_INC =
            "artist-rels+work-rels+work-level-rels+artists+releases+release-groups+isrcs+tags+genres+ratings"

        /** [browseReleaseGroupReleases]'s `inc=` and its bound; see its KDoc for why each is there. */
        private const val RELEASE_BROWSE_INC = "release-groups+artist-credits+labels+media"
        private const val RELEASE_BROWSE_LIMIT = 100

        /** [lookupArtistWithRels]'s `inc=`; see its KDoc. */
        private const val ARTIST_LOOKUP_INC = "tags+genres+aliases+ratings+url-rels+artist-rels"

        fun escapeLucene(value: String): String =
            value.replace(LUCENE_SPECIAL_CHARS) { "\\${it.value}" }

        /** Build a Lucene query with two fields and URL-encode the ENTIRE thing. */
        private fun buildQuery(field1: String, value1: String, field2: String, value2: String): String =
            encodeQueryValue("$field1:\"${escapeLucene(value1)}\" AND $field2:\"${escapeLucene(value2)}\"")

        /** The URL [searchReleases] requests. */
        fun releaseSearchUrl(title: String, artist: String, limit: Int): String =
            "$BASE_URL/release?query=${buildQuery("release", title, "artistname", artist)}&fmt=json&limit=$limit"

        /** The URL [searchArtists] requests. */
        fun artistSearchUrl(name: String, limit: Int): String {
            val escaped = escapeLucene(name)
            val query = encodeQueryValue("artist:\"$escaped\" OR alias:\"$escaped\"")
            return "$BASE_URL/artist?query=$query&fmt=json&limit=$limit"
        }

        /**
         * Schema-pin targets, mirroring [MusicBrainzParser.parseArtists] and
         * [MusicBrainzParser.parseReleases].
         *
         * `id`, `name` and `title` are `getString` reads, so their absence throws rather than
         * degrading; `score` decides whether a hit is accepted at all, and `status`,
         * `release-group.primary-type` and `artist-credit` are what release ranking is built from.
         */
        val SCHEMA_PIN_TARGETS: List<SchemaTarget> = listOf(
            SchemaTarget(
                provider = "musicbrainz",
                route = "artist search",
                url = artistSearchUrl("Radiohead", limit = 5),
                requiredPaths = listOf(
                    "artists[0].id",
                    "artists[0].name",
                    "artists[0].score",
                    "artists[0].sort-name",
                ),
            ),
            SchemaTarget(
                provider = "musicbrainz",
                route = "release search",
                url = releaseSearchUrl("OK Computer", "Radiohead", limit = 5),
                requiredPaths = listOf(
                    "releases[0].id",
                    "releases[0].title",
                    "releases[0].score",
                    "releases[0].status",
                    "releases[0].date",
                    "releases[0].country",
                    "releases[0].release-group.primary-type",
                    "releases[0].artist-credit[0].name",
                ),
            ),
        )
    }
}
