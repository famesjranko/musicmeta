package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.TransientIdentifierMarker
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles per-entity enrichment logic for MusicBrainz.
 * Called by [MusicBrainzProvider] after routing by request/type.
 *
 * **One instance per call**, held for that call by
 * [com.landofoz.musicmeta.engine.ProviderCallScope] — nothing the [CallMemo]s below hold outlives
 * it. They fold the lookups a request's types repeat into one call each, a repeat being a ~1.1s
 * wait on the shared limiter, and none needs a cap: one request's types resolve one album and a
 * handful of MBIDs.
 */
internal class MusicBrainzEnricher(
    private val api: MusicBrainzApi,
    private val providerId: String,
    private val minMatchScore: Int,
) {

    /**
     * One upstream answer per key for as long as this enricher lives, which is one `enrich()` call.
     *
     * The mutex is held across [fetch], not merely around the map: the engine resolves a request's
     * types as sibling `async` children, so two types asking the same question concurrently have to
     * make one call between them rather than one each.
     *
     * A thrown transient is never held — the write is on the success path — so the next type that
     * asks retries it. What an *absence* costs is the difference between the two entry points.
     */
    private class CallMemo<K : Any, V : Any> {

        private val entries = mutableMapOf<K, V>()
        private val mutex = Mutex()

        /** [fetch]'s answer for [key], held for the call whatever it is — including a negative one. */
        suspend fun get(key: K, fetch: suspend () -> V): V = mutex.withLock {
            entries[key] ?: fetch().also { entries[key] = it }
        }

        /** As [get], except a `null` from [fetch] is a genuine absence and is not held. */
        suspend fun getOrNull(key: K, fetch: suspend () -> V?): V? = mutex.withLock {
            entries[key] ?: fetch()?.also { entries[key] = it }
        }
    }

    /** Artist lookups by MBID: BAND_MEMBERS, ARTIST_LINKS and GENRE all want the same artist. */
    private val artistMemo = CallMemo<String, MusicBrainzArtist>()

    private suspend fun memoizedArtist(mbid: String): MusicBrainzArtist? =
        artistMemo.getOrNull(mbid) { api.lookupArtistWithRels(mbid) }

    /**
     * Release lookups by MBID, same shape as [artistMemo].
     * One album is looked up more than once per `enrich()`: GENRE resolves it as identity and again
     * in the fan-out the identity MBID enables, and ALBUM_TRACKS wants the same response a third
     * time.
     */
    private val releaseMemo = CallMemo<String, MusicBrainzRelease>()

    private suspend fun memoizedRelease(mbid: String): MusicBrainzRelease? =
        releaseMemo.getOrNull(mbid) { api.lookupRelease(mbid) }

    /**
     * Release-group Wikidata/Wikipedia relations by release-group MBID, same shape as [releaseMemo].
     * A release search never embeds these (they live on the release-group, not the release), so this
     * is a miss on the first type resolved for an album and a hit for every other type in the same
     * enrichment — same amortized cost as the artist bio path's `needsRelations` lookup.
     *
     * `(null, null)` is a real answer — "this release-group has no wiki links" — so it is held.
     */
    private val releaseGroupWikiMemo = CallMemo<String, Pair<String?, String?>>()

    private suspend fun memoizedReleaseGroupWiki(releaseGroupMbid: String): Pair<String?, String?> =
        releaseGroupWikiMemo.get(releaseGroupMbid) { api.lookupReleaseGroupWikiLinks(releaseGroupMbid) }

    internal suspend fun enrichAlbum(
        request: EnrichmentRequest.ForAlbum, type: EnrichmentType,
    ): EnrichmentResult {
        if (type in ARTIST_NEW_TYPES || type == EnrichmentType.CREDITS) {
            return EnrichmentResult.NotFound(type, providerId)
        }
        if (type == EnrichmentType.ALBUM_TRACKS) return enrichAlbumTracks(request)
        if (type == EnrichmentType.RELEASE_EDITIONS) return enrichAlbumEditions(request)
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = memoizedRelease(mbid)
                ?: return EnrichmentResult.NotFound(type, providerId)
            return buildAlbumResult(full, type, ConfidenceCalculator.idBasedLookup())
        }
        val search = memoizedAlbumSearch(request.title, request.artist)
        val best = search.release ?: return notFoundWithSuggestions(
            type, search.originalPool,
            fuzzy = { memoizedFuzzyReleases(request.title, request.artist) },
        ) { it.toCandidate() }
        // A search hit carries tags only when its release group happens to have them; the release
        // lookup is what fills them. GENRE is the one type that reads them and the one this path
        // reaches — LABEL is answered from the identity payload and never gets here.
        val resolved = if (type == EnrichmentType.GENRE && best.tags.isEmpty()) {
            memoizedRelease(best.id) ?: best
        } else {
            best
        }
        return buildAlbumResult(resolved, type, ConfidenceCalculator.searchScore(best.score))
    }

    internal suspend fun enrichAlbumTracks(
        request: EnrichmentRequest.ForAlbum,
    ): EnrichmentResult {
        val type = EnrichmentType.ALBUM_TRACKS
        val mbid = request.identifiers.musicBrainzId
            ?: memoizedAlbumSearch(request.title, request.artist).release?.id
            ?: return EnrichmentResult.NotFound(type, providerId)
        val release = memoizedRelease(mbid)
            ?: return EnrichmentResult.NotFound(type, providerId)
        if (release.tracks.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type, data = MusicBrainzMapper.toTracklist(release.tracks),
            provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release),
        )
    }

    internal suspend fun enrichAlbumEditions(
        request: EnrichmentRequest.ForAlbum,
    ): EnrichmentResult {
        val type = EnrichmentType.RELEASE_EDITIONS
        val releaseGroupMbid = request.identifiers.musicBrainzReleaseGroupId
            ?: return EnrichmentResult.NotFound(type, providerId)
        val json = api.lookupReleaseGroup(releaseGroupMbid)
            ?: return EnrichmentResult.NotFound(type, providerId)
        val detail = MusicBrainzCreditParser.parseReleaseGroupDetail(json)
        if (detail.releases.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toReleaseEditions(detail),
            provider = providerId,
            confidence = ConfidenceCalculator.idBasedLookup(),
        )
    }

    internal suspend fun enrichArtist(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        // New types with specialized API calls
        if (type in ARTIST_NEW_TYPES) {
            return enrichArtistNewType(request, type)
        }

        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = memoizedArtist(mbid)
                ?: return EnrichmentResult.NotFound(type, providerId)
            return buildArtistResult(full, type, ConfidenceCalculator.idBasedLookup())
        }

        val artists = api.searchArtists(request.name)
        if (artists.isEmpty()) {
            return notFoundWithSuggestions(
                type, artists,
                fuzzy = { api.searchArtistsFuzzy(request.name, MAX_SUGGESTIONS) },
            ) { it.toCandidate() }
        }

        val best = pickBestArtist(request.name, artists)
        if (best.score < minMatchScore) {
            return notFoundWithSuggestions(
                type, artists,
                fuzzy = { api.searchArtistsFuzzy(request.name, MAX_SUGGESTIONS) },
            ) { it.toCandidate() }
        }

        // Search results have metadata (genres, country) but lack URL relations
        // (wikidata, wikipedia). Do the full lookup when these are missing so
        // downstream providers (Wikidata, Wikipedia) can use them.
        val needsRelations = best.wikidataId == null && best.wikipediaTitle == null
        val resolved = if (needsRelations) resolveArtistRelations(best) else best

        return buildArtistResult(resolved, type, ConfidenceCalculator.searchScore(best.score))
    }

    /**
     * Best-effort, mirroring [resolveReleaseGroupWikiLinks]'s shape: a transient on the full-artist
     * lookup must not fail the type being resolved (GENRE, LABEL, …) just because it also happens to
     * carry wikidata/wikipedia relations as a byproduct — it degrades to [best] (the search hit,
     * already a valid [MusicBrainzArtist]) instead of propagating.
     */
    // SwallowedException: intentional — see the KDoc above, matching resolveReleaseGroupWikiLinks.
    @Suppress("SwallowedException")
    private suspend fun resolveArtistRelations(best: MusicBrainzArtist): MusicBrainzArtist = try {
        memoizedArtist(best.id) ?: best
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        // Same reasoning as resolveReleaseGroupWikiLinks: this run's wikidataId/wikipediaTitle for
        // this artist came back unresolved because of a transient, not a genuine absence.
        currentCoroutineContext()[TransientIdentifierMarker]?.mark(
            IdentifierRequirement.WIKIDATA_ID,
            IdentifierRequirement.WIKIPEDIA_TITLE,
        )
        best
    }

    internal suspend fun enrichArtistNewType(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val mbid = request.identifiers.musicBrainzId ?: run {
            val artists = api.searchArtists(request.name)
            val best = pickBestArtist(request.name, artists)
            if (best.score >= minMatchScore) best.id else null
        } ?: return EnrichmentResult.NotFound(type, providerId)

        return when (type) {
            EnrichmentType.BAND_MEMBERS -> {
                val artist = memoizedArtist(mbid)
                    ?: return EnrichmentResult.NotFound(type, providerId)
                val members = if (artist.bandMembers.isNotEmpty()) {
                    MusicBrainzMapper.toBandMembers(artist.bandMembers)
                } else if (artist.type == "Person") {
                    MusicBrainzMapper.toSoloArtistMember(artist)
                } else {
                    return EnrichmentResult.NotFound(type, providerId)
                }
                EnrichmentResult.Success(
                    type = type,
                    data = members,
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            EnrichmentType.ARTIST_DISCOGRAPHY -> {
                val groups = api.browseReleaseGroups(mbid)
                if (groups.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toDiscography(groups),
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }
            EnrichmentType.ARTIST_LINKS -> {
                val artist = memoizedArtist(mbid)
                    ?: return EnrichmentResult.NotFound(type, providerId)
                if (artist.urlRelations.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toArtistLinks(artist.urlRelations),
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            else -> EnrichmentResult.NotFound(type, providerId)
        }
    }

    internal suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (type == EnrichmentType.CREDITS) return enrichTrackCredits(request)

        val recordings = api.searchRecordings(request.title, request.artist, request.album)
        val best = pickBestRecording(request.title, recordings, request.album)
            ?: resolveTrackQualifierFallback(request.title, request.artist, request.album)
            // searchRecordings' own hint-less retry re-sends the title quoted (see its KDoc), so
            // only the fuzzy search can rescue a typo — and only an empty strict pool triggers it.
            ?: return notFoundWithSuggestions(
                type, recordings,
                fuzzy = { api.searchRecordingsFuzzy(request.title, request.artist, MAX_SUGGESTIONS) },
            ) { it.toCandidate() }

        val data = if (type == EnrichmentType.TRACK_METADATA) {
            MusicBrainzMapper.toTrackMetadataDetails(best)
        } else {
            MusicBrainzMapper.toTrackMetadata(best)
        }

        return EnrichmentResult.Success(
            type = type,
            data = data,
            provider = providerId,
            confidence = ConfidenceCalculator.searchScore(best.score),
            resolvedIdentifiers = MusicBrainzMapper.toTrackIdentifiers(best),
        )
    }

    internal suspend fun enrichTrackCredits(
        request: EnrichmentRequest.ForTrack,
    ): EnrichmentResult {
        val type = EnrichmentType.CREDITS
        val mbid = request.identifiers.musicBrainzId
            ?: return EnrichmentResult.NotFound(type, providerId)
        val json = api.lookupRecording(mbid)
            ?: return EnrichmentResult.NotFound(type, providerId)
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)
        if (credits.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toCredits(credits),
            provider = providerId,
            confidence = ConfidenceCalculator.idBasedLookup(),
        )
    }

    private suspend fun buildAlbumResult(
        release: MusicBrainzRelease,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult.Success {
        val (wikidataId, wikipediaTitle) = resolveReleaseGroupWikiLinks(release.releaseGroupId)
        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toAlbumMetadata(release),
            provider = providerId,
            confidence = confidence,
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release, wikidataId, wikipediaTitle),
        )
    }

    /**
     * Best-effort: [type] here is whatever the caller actually asked for (GENRE, LABEL, …), never
     * `ALBUM_DESCRIPTION` — MusicBrainz has no capability for it, so this side lookup only ever runs
     * as a byproduct of resolving a different type's *own*, already-successful data. A transient
     * failure here must not fail that unrelated type: it would turn a legitimate `Success` into an
     * `Error`, record a MusicBrainz breaker failure for a hiccup that had nothing to do with the
     * type being resolved, and — since identity resolution still fans out to every requested type on
     * an `Error` (`DefaultEnrichmentEngine`) — drop the resolved identifiers the *rest* of that run's
     * types would otherwise have used. So this degrades to "unresolved this call" instead of
     * propagating, mirroring [bodyOrThrowTransient]'s own split (null only for a genuine
     * [com.landofoz.musicmeta.http.HttpResult.ClientError] absence) one level up.
     *
     * The transient is never written to [releaseGroupWikiMemo] (the write only happens on the
     * success path inside [memoizedReleaseGroupWiki]), so it is retried — not pinned as "no
     * wiki links" — by the next type in this call that resolves this release-group.
     */
    // SwallowedException: intentional — see the KDoc above. This enricher has no logger to hand the
    // exception to; degrading silently is the fix, not an oversight (detekt cannot tell them apart).
    @Suppress("SwallowedException")
    private suspend fun resolveReleaseGroupWikiLinks(releaseGroupMbid: String?): Pair<String?, String?> {
        if (releaseGroupMbid == null) return null to null
        return try {
            memoizedReleaseGroupWiki(releaseGroupMbid)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            // This run's wikidataId/wikipediaTitle came back unresolved because of a transient, not
            // because this release-group genuinely has none — record that so a type gated on either
            // (e.g. ALBUM_DESCRIPTION's Wikipedia requirement) can be told apart from a genuine
            // absence and reclassified to Error instead of a cacheable NotFound.
            currentCoroutineContext()[TransientIdentifierMarker]?.mark(
                IdentifierRequirement.WIKIDATA_ID,
                IdentifierRequirement.WIKIPEDIA_TITLE,
            )
            null to null
        }
    }

    private fun buildArtistResult(
        artist: MusicBrainzArtist,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult.Success = EnrichmentResult.Success(
        type = type,
        data = MusicBrainzMapper.toArtistMetadata(artist),
        provider = providerId,
        confidence = confidence,
        resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
    )

    /**
     * Rank artist candidates: exact name match with tags > exact name match >
     * has tags with high score > highest score.
     */
    private fun pickBestArtist(
        query: String,
        candidates: List<MusicBrainzArtist>,
    ): MusicBrainzArtist = candidates.sortedByDescending { artist ->
        val exactMatch = artist.name.equals(query, ignoreCase = true)
        val hasTags = artist.tags.isNotEmpty()
        when {
            exactMatch && hasTags -> 3
            exactMatch -> 2
            hasTags -> 1
            else -> 0
        }
    }.first()

    /**
     * Rank the recording pool above [minMatchScore] instead of taking `firstOrNull` — MB search
     * ties score-100 hits and puts demo/live/bootleg/cover takes ahead of the studio original
     * (`docs/pitfalls.md` §7). Among survivors, highest tier first, keeping pool order among ties
     * (`maxWithOrNull` keeps the first maximum, same convention as [pickBestArtist]'s sibling in
     * `DeezerApi.rankTracks`):
     *
     * 1. exact (case-insensitive) title match against [title] — verified live: a per-member
     *    cover/karaoke recording titled e.g. "Enter Sandman (Ulrich)" carries no disambiguation at
     *    all and would still beat the studio original on tiers 2/3 alone, so title has to be
     *    checked first, same tier order as `DeezerApi.rankTracks`'s tier 1
     * 2. [albumTitle] present and matches (via [MusicBrainzRecording.artReleaseGroupTitle], already
     *    tier-0-preferring an exact album match — see `MusicBrainzParser.findArtReleaseGroup`) — an
     *    explicit album request is the strongest available signal, so it outranks both the
     *    disambiguation and video checks below. No-op (constant `false` for every candidate) when
     *    [albumTitle] is null, so an album-less request falls straight through to tier 3.
     * 3. not [MusicBrainzRecording.isVideo] — MB's own structural flag for a music-video take, not
     *    a keyword match on disambiguation text (`docs/pitfalls.md` §7's rejected pattern):
     *    verified live, Radiohead's "Karma Police" music-video recording is the *only* exact-title,
     *    score-100 hit for an album-hinted "OK Computer" search, so it would otherwise win tier 1
     *    outright with nothing to lose to.
     * 4. blank [MusicBrainzRecording.disambiguation] — a canonical recording carries none; ANY
     *    disambiguation marks a variant. MB's disambiguation vocabulary is open (demo, live,
     *    "bootleg edited version", instrumental, acoustic, radio edit, mono/stereo, single
     *    version, …) and cannot be enumerated by keyword — verified live: "bootleg edited version"
     *    isn't a "demo"/"live"/"remix"/"remaster" keyword match, so a keyword list let it tie the
     *    studio original and win on pool order. Blank-vs-non-blank has no such gap. The accepted
     *    edge: a request that explicitly asks for a variant edition (title itself names it) still
     *    resolves via tier 1's exact-title match, because MB puts variant information in
     *    disambiguation, not in the recording title — blank-preference never fights an exact title.
     * 5. carries an Official release on an Album release-group
     *    ([MusicBrainzRecording.hasOfficialAlbumRelease]) — prefers the studio album cut over a
     *    single/compilation-only recording when neither carries a disambiguation
     *
     * The score floor itself is relaxed for a candidate whose [albumTitle] matches: verified live,
     * Radiohead's actual studio "Karma Police" recording (the one the "OK Computer" release itself
     * carries) scores only 77 under MB's own relevance ranking — well below the default 80 — while
     * the wrong (music-video) exact-title candidate scores 100. An explicit, request-supplied album
     * match is independent evidence of correctness that MB's fuzzy text-relevance score doesn't
     * capture, so it is allowed to override the floor rather than leaving the correct candidate
     * filtered out before ranking ever sees it.
     */
    private fun pickBestRecording(
        title: String,
        recordings: List<MusicBrainzRecording>,
        albumTitle: String? = null,
    ): MusicBrainzRecording? {
        val album = albumTitle?.trim()?.takeIf { it.isNotBlank() }
        return recordings
            .filter { it.score >= minMatchScore || (album != null && it.matchesAlbum(album)) }
            .map { it to it.recordingRank(title, album) }
            .maxWithOrNull(
                compareBy(
                    { it.second.exactTitle }, { it.second.albumMatch }, { it.second.notVideo },
                    { it.second.blankDisambiguation }, { it.second.officialAlbum },
                ),
            )
            ?.first
    }

    private fun MusicBrainzRecording.matchesAlbum(album: String): Boolean =
        artReleaseGroupTitle?.trim()?.equals(album, ignoreCase = true) == true

    private fun MusicBrainzRecording.recordingRank(title: String, album: String?) = RecordingRank(
        exactTitle = this.title.trim().equals(title.trim(), ignoreCase = true),
        albumMatch = album != null && matchesAlbum(album),
        notVideo = !isVideo,
        blankDisambiguation = disambiguation.isNullOrBlank(),
        officialAlbum = hasOfficialAlbumRelease,
    )

    private data class RecordingRank(
        val exactTitle: Boolean,
        val albumMatch: Boolean,
        val notVideo: Boolean,
        val blankDisambiguation: Boolean,
        val officialAlbum: Boolean,
    )

    /** [release]: the resolved match, if any; [originalPool]: [request.title]'s own search results, for suggestions. */
    private data class AlbumSearchResult(val release: MusicBrainzRelease?, val originalPool: List<MusicBrainzRelease>)

    /**
     * Album resolution by title/artist: the whole of [searchAlbum]'s ladder, which every album type
     * of one request otherwise re-runs — up to an artist search and [SYMBOL_FALLBACK_MAX_PAGES]
     * browse pages each time, all on the shared limiter. Unlike the memos above it holds *which*
     * album a title resolves to, which is only safe because nothing here outlives the call.
     *
     * A result that resolved nothing is held like any other, and matters more here than elsewhere:
     * an empty result is what pays for both fallbacks in full, so it is the repeat worth collapsing
     * most.
     */
    private val albumSearchMemo = CallMemo<AlbumQuery, AlbumSearchResult>()

    /**
     * Shared by [enrichAlbum] and [enrichAlbumTracks], which both need identical album-resolution
     * semantics.
     */
    private suspend fun memoizedAlbumSearch(title: String, artist: String): AlbumSearchResult =
        albumSearchMemo.get(albumQuery(title, artist)) { searchAlbum(title, artist) }

    /**
     * Near-miss suggestions for an album title nothing strict resolves, keyed as [albumSearchMemo]
     * is. [notFoundWithSuggestions] asks for these whenever the strict pool is empty, which — for an
     * album MusicBrainz does not hold — is once per album type of the request. The pool that decides
     * they are needed is memoized, so this has to be as well, or an absent album pays a full
     * `release?query=` per type for the same three suggestions.
     */
    private val albumFuzzyMemo = CallMemo<AlbumQuery, List<MusicBrainzRelease>>()

    private suspend fun memoizedFuzzyReleases(title: String, artist: String): List<MusicBrainzRelease> =
        albumFuzzyMemo.get(albumQuery(title, artist)) {
            api.searchReleasesFuzzy(title, artist, MAX_SUGGESTIONS)
        }

    /**
     * [albumSearchMemo] and [albumFuzzyMemo]'s key. Two fields rather than one joined string:
     * [MusicBrainzQualifierFallback.normalize] collapses whitespace, so a joined key would need a
     * separator no title can contain.
     */
    private data class AlbumQuery(val title: String, val artist: String)

    private fun albumQuery(title: String, artist: String) = AlbumQuery(
        MusicBrainzQualifierFallback.normalize(title),
        MusicBrainzQualifierFallback.normalize(artist),
    )

    /**
     * Resolves an album search, trying [title]/[artist] as-is first — unchanged from today's
     * behavior — and only falling back to [MusicBrainzQualifierFallback]'s progressively-stripped
     * candidates when the direct search finds nothing at or above [minMatchScore], then to
     * [resolveAlbumSymbolFallback] — but only on an *empty* pool: a populated pool that merely
     * missed the score floor means the title is searchable and the album is not there, so that
     * fallback's extra calls would buy nothing.
     */
    private suspend fun searchAlbum(title: String, artist: String): AlbumSearchResult {
        val releases = api.searchReleases(title, artist)
        val direct = MusicBrainzReleaseRanking.pickBestRelease(releases, minMatchScore)
        val resolved = direct
            ?: resolveAlbumQualifierFallback(title, artist)
            ?: if (releases.isEmpty()) resolveAlbumSymbolFallback(title, artist) else null
        return AlbumSearchResult(resolved, releases)
    }

    /**
     * Tries each of [MusicBrainzQualifierFallback]'s fallback candidates (dropping the original
     * title — [memoizedAlbumSearch] already tried that) in most-specific-first order, stopping at the
     * first one [resolve] resolves. Shared shape for [resolveAlbumQualifierFallback] and
     * [resolveTrackQualifierFallback], which differ only in how a candidate resolves.
     */
    private suspend fun <T> resolveViaQualifierFallback(
        title: String,
        resolve: suspend (MusicBrainzQualifierFallback.FallbackCandidate) -> T?,
    ): T? {
        for (candidate in MusicBrainzQualifierFallback.qualifierFallbackCandidates(title).drop(1)) {
            resolve(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Searches MB for each fallback candidate's exact text and requires an "authoritative" hit: at
     * or above [minMatchScore], normalized title equality with the searched candidate (score alone
     * is not proof of identity — quoted Lucene is phrase search, not string equality), and a
     * matching credited artist. Survivors go through [MusicBrainzReleaseRanking.pickBestRelease],
     * the same ladder the direct path uses, carrying the tags stripped to reach this candidate.
     */
    private suspend fun resolveAlbumQualifierFallback(title: String, artist: String): MusicBrainzRelease? {
        val artistNorm = MusicBrainzQualifierFallback.normalize(artist)
        return resolveViaQualifierFallback(title) { candidate ->
            val candidateNorm = MusicBrainzQualifierFallback.normalize(candidate.title)
            val authoritative = api.searchReleases(candidate.title, artist).filter {
                it.score >= minMatchScore &&
                    MusicBrainzQualifierFallback.normalize(it.title) == candidateNorm &&
                    anyArtistMatches(it.artistCredits, artistNorm)
            }
            MusicBrainzReleaseRanking.pickBestRelease(authoritative, minMatchScore, candidate.removedTags)
        }
    }

    /**
     * Last resort for a title no ASCII spelling can search for (`"F♯ A♯ ∞"`): the title is not
     * searched at all, the artist's release groups are browsed and matched locally on
     * [MusicBrainzTitleFolding.fold]. Costs an artist search, up to [SYMBOL_FALLBACK_MAX_PAGES]
     * browse pages and one release search, all on the shared limiter; an album past that cap still
     * needs an MBID.
     *
     * Identity never rests on the fold: the final search uses the release group's own title, and a
     * survivor must sit in that group, credit the requested artist and clear [minMatchScore]
     * (`docs/pitfalls.md` §7 — score is not proof of identity).
     */
    private suspend fun resolveAlbumSymbolFallback(title: String, artist: String): MusicBrainzRelease? {
        val artistNorm = MusicBrainzQualifierFallback.normalize(artist)
        val artistMbid = resolveArtistMbidForFallback(artist, artistNorm) ?: return null
        val group = findReleaseGroupByFoldedTitle(artistMbid, title) ?: return null
        val groupTitleFold = MusicBrainzTitleFolding.fold(group.title)
        val authoritative = api.searchReleases(group.title, artist).filter {
            it.score >= minMatchScore &&
                it.releaseGroupId == group.id &&
                MusicBrainzTitleFolding.fold(it.title) == groupTitleFold &&
                anyArtistMatches(it.artistCredits, artistNorm)
        }
        return MusicBrainzReleaseRanking.pickBestRelease(authoritative, minMatchScore)
    }

    /**
     * The artist MBID to browse, or null unless the artist resolves to *exactly* the requested name
     * — stricter than [enrichArtist], because a near-miss would scope the browse to the wrong
     * catalogue and nothing downstream would catch it.
     */
    private suspend fun resolveArtistMbidForFallback(artist: String, artistNorm: String): String? {
        val artists = api.searchArtists(artist)
        if (artists.isEmpty()) return null
        val best = pickBestArtist(artist, artists)
        val exact = best.score >= minMatchScore && MusicBrainzQualifierFallback.normalize(best.name) == artistNorm
        return best.id.takeIf { exact }
    }

    /**
     * The artist's first release group whose folded title equals [title]'s, paging until a short
     * page ends the catalogue or [SYMBOL_FALLBACK_MAX_PAGES] pages are read. A group that merely
     * normalizes equal is skipped — the direct search already tried that spelling and got nothing.
     */
    private suspend fun findReleaseGroupByFoldedTitle(
        artistMbid: String,
        title: String,
    ): MusicBrainzReleaseGroup? {
        val titleFold = MusicBrainzTitleFolding.fold(title)
        val titleNorm = MusicBrainzQualifierFallback.normalize(title)
        for (page in 0 until SYMBOL_FALLBACK_MAX_PAGES) {
            val groups = api.browseReleaseGroups(
                artistMbid,
                MusicBrainzApi.BROWSE_PAGE_SIZE,
                page * MusicBrainzApi.BROWSE_PAGE_SIZE,
            )
            groups.firstOrNull {
                MusicBrainzTitleFolding.fold(it.title) == titleFold &&
                    MusicBrainzQualifierFallback.normalize(it.title) != titleNorm
            }?.let { return it }
            if (groups.size < MusicBrainzApi.BROWSE_PAGE_SIZE) return null
        }
        return null
    }

    /**
     * Same qualifier-fallback candidate search as [resolveAlbumQualifierFallback] — requires the
     * same "authoritative" hit (score floor, normalized title equality, matching credited artist;
     * score alone is not proof of identity) before ranking survivors — but for recordings, reuses
     * [pickBestRecording]'s existing ranking on the authoritative pool rather than introducing a
     * second, parallel tie-break primitive: recordings already have a tie-break shaped for their own
     * signals (video flag, official-album release, blank disambiguation), which the generic kind/year
     * tag tie-break would not improve on.
     */
    private suspend fun resolveTrackQualifierFallback(
        title: String,
        artist: String,
        album: String?,
    ): MusicBrainzRecording? {
        val artistNorm = MusicBrainzQualifierFallback.normalize(artist)
        return resolveViaQualifierFallback(title) { candidate ->
            val candidateNorm = MusicBrainzQualifierFallback.normalize(candidate.title)
            val authoritative = api.searchRecordings(candidate.title, artist, album).filter {
                it.score >= minMatchScore &&
                    MusicBrainzQualifierFallback.normalize(it.title) == candidateNorm &&
                    anyArtistMatches(it.artistCredits, artistNorm)
            }
            pickBestRecording(candidate.title, authoritative, album)
        }
    }

    private fun anyArtistMatches(credits: List<String>, expectedNorm: String): Boolean =
        credits.any { MusicBrainzQualifierFallback.normalize(it) == expectedNorm }

    private fun MusicBrainzRelease.toCandidate() = SearchCandidate(
        title = title, artist = artistCredit, year = date,
        country = country, releaseType = releaseType, score = score,
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id, musicBrainzReleaseGroupId = releaseGroupId),
        disambiguation = disambiguation,
    )

    private fun MusicBrainzArtist.toCandidate() = SearchCandidate(
        title = name, artist = null, year = beginDate,
        country = country, releaseType = type, score = score,
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id),
        disambiguation = disambiguation,
    )

    /**
     * [year]/[country]/[releaseType] are null because a recording search hit carries none of its
     * own — those live on its releases, and picking "the" release needs a lookup this class never
     * does. [thumbnailUrl] is null because a recording has no [MusicBrainzRelease.hasFrontCover]
     * equivalent to tell a real cover from a CAA 404.
     */
    private fun MusicBrainzRecording.toCandidate() = SearchCandidate(
        title = title, artist = artistCredit, year = null,
        country = null, releaseType = null, score = score,
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id, musicBrainzReleaseGroupId = artReleaseGroupId),
        disambiguation = disambiguation,
    )

    /**
     * The shared NotFound cascade: an empty strict [pool] asks [fuzzy] for near-misses; a
     * non-empty pool that still failed to rank is suggested as-is.
     */
    private suspend fun <T> notFoundWithSuggestions(
        type: EnrichmentType,
        pool: List<T>,
        fuzzy: suspend () -> List<T>,
        toCandidate: (T) -> SearchCandidate,
    ): EnrichmentResult.NotFound = if (pool.isEmpty()) {
        EnrichmentResult.NotFound(type, providerId,
            suggestions = fuzzy().takeIf { it.isNotEmpty() }?.take(MAX_SUGGESTIONS)?.map(toCandidate))
    } else {
        EnrichmentResult.NotFound(type, providerId,
            suggestions = pool.take(MAX_SUGGESTIONS).map(toCandidate))
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3

        /**
         * Browse pages [findReleaseGroupByFoldedTitle] reads before giving up. One is not enough:
         * Godspeed You! Black Emperor has 107 album/EP/single release groups (live, 2026-08-10).
         */
        internal const val SYMBOL_FALLBACK_MAX_PAGES = 3

        /** New artist types routed through enrichArtistNewType(). */
        private val ARTIST_NEW_TYPES = setOf(
            EnrichmentType.BAND_MEMBERS,
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentType.ARTIST_LINKS,
        )
    }
}
