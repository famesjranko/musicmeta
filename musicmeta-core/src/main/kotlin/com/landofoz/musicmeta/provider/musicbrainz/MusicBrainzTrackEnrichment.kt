package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.CallMemo
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.artistBlanksNameSearch
import com.landofoz.musicmeta.engine.namesNoEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Resolves the recording a request names or identifies, and answers every track type off it —
 * including the ranking that decides which take of a title a pool means.
 *
 * Holds one call's track memos, so the invariant is [MusicBrainzEnricher]'s: one
 * instance per call, and nothing memoized here outlives it.
 */
internal class MusicBrainzTrackEnrichment(
    private val api: MusicBrainzApi,
    private val providerId: String,
    private val minMatchScore: Int,
) {

    /**
     * Raw recording lookups by MBID, same shape as `MusicBrainzAlbumEnrichment.releaseMemo` — held
     * raw because CREDITS and the recording's own fields parse the same response two different ways
     * ([MusicBrainzApi.lookupRecording]), so one call serves every track type of a request that
     * carries an MBID.
     */
    private val recordingMemo = CallMemo<String, MusicBrainzLookup<JSONObject>>()

    internal suspend fun memoizedRecording(mbid: String): MusicBrainzLookup<JSONObject> =
        recordingMemo.get(mbid) { api.lookupRecording(mbid) }

    /**
     * Recording ids [enrichTrack] resolved *by search* during this call.
     *
     * This is what tells a caller's MBID apart from the engine's own echo of one. Identity
     * resolution runs on this instance before the fan-out
     * ([com.landofoz.musicmeta.engine.DefaultEnrichmentEngine]) and merges the recording it picked
     * into the request, so every type then sees an MBID that was not there when the call started.
     * Looking *that* up would change which release-group answers a name-only request; looking up
     * one that came from outside the call is what this path exists to do. Nothing else can draw the
     * line — the request carries no provenance, and does not need to.
     *
     * Guarded like [CallMemo]'s map, and for the same reason: sibling types resolve as concurrent
     * `async` children.
     */
    private val searchResolvedRecordings = mutableSetOf<String>()
    private val searchResolvedMutex = Mutex()

    private suspend fun rememberSearchResolved(recordingId: String) {
        searchResolvedMutex.withLock { searchResolvedRecordings.add(recordingId) }
    }

    private suspend fun isOwnSearchEcho(mbid: String): Boolean =
        searchResolvedMutex.withLock { mbid in searchResolvedRecordings }

    /** As [AlbumSearchResult.provenance], for [TrackSearchResult]. */
    private fun TrackSearchResult.provenance(requestedTitle: String): LookupProvenance? =
        searchProvenance(viaQualifierFallback, requestedTitle, recording?.title)

    /** [memoizedRecording], with the caller's own artist checked against it — see [unlessDifferentArtist]. */
    private suspend fun suppliedRecording(
        request: EnrichmentRequest.ForTrack,
        mbid: String,
    ): JSONObject? = memoizedRecording(mbid).valueOrNull()?.let { json ->
        val credit = MusicBrainzParser.parseLookupRecording(json, request.album)?.artistCredit
        if (credit == null || !markIfDifferentArtist(request.artist, credit)) json else null
    }

    internal suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (type == EnrichmentType.CREDITS) return enrichTrackCredits(request)
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null && !isOwnSearchEcho(mbid)) {
            enrichTrackByMbid(request, mbid, type)?.let { return it }
        }
        return enrichTrackBySearch(request, type)
    }

    /**
     * The MBID reached this call from outside it — a caller's, or a foreign identity provider's —
     * so it names the recording the answer must describe, exactly as
     * [MusicBrainzAlbumEnrichment.enrichAlbum] and [MusicBrainzArtistEnrichment.enrichArtist] treat
     * theirs. A recording MusicBrainz *holds* is never traded for a search hit: answering with a
     * different recording is the defect this path exists to close, and that holds whether the
     * lookup's body parses or not.
     *
     * [MusicBrainzLookup.Absent] is the one case that is not a miss: MusicBrainz has stated it
     * holds no such recording under any entity type. An identifier naming nothing names no
     * recording to be faithful to, so there is no wrong-recording risk in resolving the request the
     * way one carrying no identifier at all resolves — and [enrichTrack] does exactly that with the
     * null this returns for it. Treating the two alike costs a consumer the whole track for a stale
     * third-party id, which is what these identifiers are in practice.
     *
     * Nothing here returns [trackMiss]. Its suggestions mean "name the entity you meant", which the
     * engine reads as grounds to skip the provider fan-out entirely
     * ([com.landofoz.musicmeta.engine.DefaultEnrichmentEngine]) — right for a name that resolves to
     * nothing, wrong for an identifier, whose miss must never cost the request the providers that
     * did not need it.
     */
    private suspend fun enrichTrackByMbid(
        request: EnrichmentRequest.ForTrack,
        mbid: String,
        type: EnrichmentType,
    ): EnrichmentResult? {
        val json = suppliedRecording(request, mbid) ?: return null
        val recording = MusicBrainzParser.parseLookupRecording(json, request.album)
            ?: return EnrichmentResult.NotFound(type, providerId)
        offerNames(recording.title, recording.artistCredit)
        return trackResult(recording, type, ConfidenceCalculator.idBasedLookup(), LookupProvenance.CANONICAL_ID)
    }

    private suspend fun enrichTrackBySearch(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        // A request naming nothing is reached when an identifier-only one
        // ([EnrichmentRequest.forTrackByMbid] and siblings) named an entity MusicBrainz does not
        // hold, so identity resolution had no title to backfill. Searching the blank would let
        // whatever ranks first for a query naming nothing become this request's recording. No
        // suggestions either: a caller who supplied no name cannot be asked which one they meant,
        // and suggestions cost the whole provider fan-out.
        //
        // A blank artist is refused here too, and — unlike the album path — without searching for
        // suggestions. A recording title alone is a far weaker key than an album title: the pool a
        // blank artist opens runs to tens of thousands of takes, covers and live versions of the
        // same words, and the recording the caller meant is usually not among the ones returned.
        // Spending a request on candidates that do not contain the answer buys nothing.
        if (namesNoEntity(request) || artistBlanksNameSearch(request)) {
            return EnrichmentResult.NotFound(type, providerId)
        }
        val search = memoizedTrackSearch(request)
        val best = search.recording ?: return trackMiss(request, type)

        rememberSearchResolved(best.id)
        return trackResult(best, type, ConfidenceCalculator.searchScore(best.score), search.provenance(request.title))
    }

    /**
     * The answer when a *name* resolves to no recording. Suggestions only — nothing offered here is
     * remembered by [rememberSearchResolved] or can become the answer.
     *
     * Reached from [enrichTrackBySearch] and nowhere else, which is load-bearing rather than
     * incidental: see [enrichTrackByMbid] for what these suggestions cost a request that reaches
     * the engine's identity resolution.
     *
     * [MusicBrainzApi.searchRecordings]' own hint-less retry re-sends the title quoted (see its
     * KDoc), so only the fuzzy search can rescue a typo, and only an empty suggestion pool asks for
     * it.
     */
    private suspend fun trackMiss(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult.NotFound = notFoundWithSuggestions(
        type, providerId, memoizedTrackSuggestions(request),
        fuzzy = { memoizedFuzzyRecordings(request) },
    ) { it.toCandidate() }

    private fun trackResult(
        recording: MusicBrainzRecording,
        type: EnrichmentType,
        confidence: Float,
        provenance: LookupProvenance? = null,
    ): EnrichmentResult.Success = EnrichmentResult.Success(
        type = type,
        data = when (type) {
            // `inc=ratings` already rides RECORDING_LOOKUP_INC, so the rating costs no request.
            EnrichmentType.TRACK_POPULARITY -> MusicBrainzMapper.toPopularity(recording.rating)
            EnrichmentType.TRACK_METADATA -> MusicBrainzMapper.toTrackMetadataDetails(recording)
            else -> MusicBrainzMapper.toTrackMetadata(recording)
        },
        provider = providerId,
        confidence = confidence,
        resolvedIdentifiers = MusicBrainzMapper.toTrackIdentifiers(recording),
        provenance = provenance,
    )

    /**
     * Credits are read off a recording lookup and never off a search, so every miss here is bare —
     * including an identifier MusicBrainz does not hold, which [enrichTrackByMbid] answers by
     * resolving the request as if it carried no identifier. This path has no such answer to fall
     * back to: a request carrying no identifier is already a [EnrichmentResult.NotFound]. In a full
     * [com.landofoz.musicmeta.engine.EnrichmentEngine.enrich] the point is close to moot, because
     * identity resolution replaces the dead identifier with the one it resolved before this runs.
     *
     * A recording it *does* hold that credits nobody is a different miss again: the caller's
     * identifier resolved, and offering other recordings would answer "did you mean a different
     * track?" when the answer is "this track, and it credits nobody".
     */
    internal suspend fun enrichTrackCredits(
        request: EnrichmentRequest.ForTrack,
    ): EnrichmentResult {
        val type = EnrichmentType.CREDITS
        val mbid = request.identifiers.musicBrainzId
            ?: return EnrichmentResult.NotFound(type, providerId)
        val json = suppliedRecording(request, mbid)
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

    /**
     * Rank the recording pool above [minMatchScore] instead of taking `firstOrNull` — MB search
     * ties score-100 hits and puts demo/live/bootleg/cover takes ahead of the studio original
     * (`docs/pitfalls.md` §7). Among survivors, highest tier first, keeping pool order among ties
     * (`maxWithOrNull` keeps the first maximum, same convention as [pickBestArtist]'s sibling in
     * `DeezerApi.rankTracks`):
     *
     * 0. [ArtistMatcher.matchQuality] against [artist], the best of any credited name
     *    ([MusicBrainzRecording.artistCredits], one entry per credit with no joinphrase to
     *    misparse) — leads every other tier, same as [pickBestArtist]'s own name tier. This is the
     *    opposite of `DeezerApi.rankTracks`, which puts artist quality at its tier 2, *below* exact
     *    title — but that pool is filtered by `ArtistMatcher.isMatch` before ranking ever starts
     *    (`docs/pitfalls.md` §7), and this one is not: this is the direct search path §7's own
     *    convention doesn't cover, so a recording credited to someone else must lose before title
     * or
     *    edition signals get a vote, not after. Ranks rather than rejects: a pool with no
     *    matching-artist candidate at all still resolves to its best title/edition match, tied at
     *    [ArtistMatcher.QUALITY_NONE].
     * 1. exact (case-insensitive) title match against [title] — verified live: a per-member
     *    cover/karaoke recording titled e.g. "Enter Sandman (Ulrich)" carries no disambiguation at
     *    all and would still beat the studio original on tiers 2/3 alone, so title has to be
     *    checked before them
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
     * 4. blank [MusicBrainzRecording.disambiguation] — normally decided upstream, since
     *    [MusicBrainzApi.searchCanonicalRecordings] asks MusicBrainz for exactly this tier and a
     *    filtered pool is all blanks; it still fires on the unfiltered pools that reach here (the
     *    qualifier fallback's, and the fallback when the filter empties the pool). A canonical
     *    recording carries no disambiguation; ANY
     *    disambiguation marks a variant. MB's disambiguation vocabulary is open (demo, live,
     *    "bootleg edited version", instrumental, acoustic, radio edit, mono/stereo, single
     *    version, …) and cannot be enumerated by keyword — verified live: "bootleg edited version"
     *    isn't a "demo"/"live"/"remix"/"remaster" keyword match, so a keyword list let it tie the
     *    studio original and win on pool order. Blank-vs-non-blank has no such gap. A request that
     *    explicitly asks for a variant edition (title itself names it) resolves on tier 1 instead,
     *    which outranks this one — but only while that recording is in the pool, and MB routinely
     *    repeats the variant in the disambiguation as well as the title, which `-comment:*` removes
     *    upstream. [MusicBrainzApi.searchCanonicalRecordings] keeps the filter off such a request
     *    for exactly that reason.
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
    internal fun pickBestRecording(
        title: String,
        recordings: List<MusicBrainzRecording>,
        albumTitle: String? = null,
        artist: String? = null,
    ): MusicBrainzRecording? {
        val album = albumTitle?.trim()?.takeIf { it.isNotBlank() }
        return recordings
            .filter { it.score >= minMatchScore || (album != null && it.matchesAlbum(album)) }
            .map { it to it.recordingRank(title, album, artist) }
            .maxWithOrNull(
                compareBy(
                    { it.second.artistQuality }, { it.second.exactTitle }, { it.second.albumMatch },
                    { it.second.notVideo }, { it.second.blankDisambiguation }, { it.second.officialAlbum },
                ),
            )
            ?.first
    }

    private fun MusicBrainzRecording.matchesAlbum(album: String): Boolean =
        artReleaseGroupTitle?.trim()?.equals(album, ignoreCase = true) == true

    /**
     * Best [ArtistMatcher.matchQuality] of [artist] against any of this recording's individually
     * credited names ([MusicBrainzRecording.artistCredits]) — a collaboration credited "Queen &
     * David Bowie" must still rank as a match for a request naming only "Queen". Null or blank
     * [artist] carries [ArtistMatcher.QUALITY_NONE], the same as no credit matching at all.
     */
    private fun MusicBrainzRecording.artistQuality(artist: String?): Int =
        if (artist.isNullOrBlank()) {
            ArtistMatcher.QUALITY_NONE
        } else {
            artistCredits.maxOfOrNull { ArtistMatcher.matchQuality(artist, it) } ?: ArtistMatcher.QUALITY_NONE
        }

    private fun MusicBrainzRecording.recordingRank(title: String, album: String?, artist: String?) = RecordingRank(
        artistQuality = artistQuality(artist),
        exactTitle = this.title.trim().equals(title.trim(), ignoreCase = true),
        albumMatch = album != null && matchesAlbum(album),
        notVideo = !isVideo,
        blankDisambiguation = disambiguation.isNullOrBlank(),
        officialAlbum = hasOfficialAlbumRelease,
    )

    private data class RecordingRank(
        val artistQuality: Int,
        val exactTitle: Boolean,
        val albumMatch: Boolean,
        val notVideo: Boolean,
        val blankDisambiguation: Boolean,
        val officialAlbum: Boolean,
    )

    /**
     * [searchTrack]'s answer. [viaQualifierFallback] is true exactly when [recording] was reached
     * by searching a [MusicBrainzQualifierFallback] stripped candidate rather than the caller's
     * literal title — the truthful signal [enrichTrackBySearch] self-reports
     * [com.landofoz.musicmeta.LookupProvenance.QUALIFIER_FALLBACK_NAME] from.
     */
    private data class TrackSearchResult(
        val recording: MusicBrainzRecording?,
        val viaQualifierFallback: Boolean = false,
    )

    /**
     * Track resolution by title/artist/album: [searchTrack]'s whole ladder, which every track type
     * of one request otherwise re-runs — the search plus, on an empty pool,
     * [resolveTrackQualifierFallback]'s own searches. Keyed like
     * `MusicBrainzAlbumEnrichment.albumSearchMemo`, and holding which recording a name resolves to
     * under the same protection: nothing here outlives the call.
     *
     * A track repeats this search where an album does not. Identity resolution merges the recording
     * it picked into the request, and [enrichTrack] deliberately routes that MBID back to the
     * search rather than looking it up ([isOwnSearchEcho]), so identity's query and every type's
     * query are the same one. Two of them are not equivalent to one: MusicBrainz does not order
     * identical searches identically, and [pickBestRecording] keeps the first maximum among ties,
     * so a second search ranks a differently-ordered pool and can pick a different recording —
     * leaving the identity a consumer reads naming one recording while its payload describes
     * another.
     *
     * A result that resolved nothing is held like any other, and matters more here than elsewhere:
     * an empty result is what pays for the qualifier fallback in full, so it is the repeat worth
     * collapsing most — the fallback runs from inside this memo rather than at each call site, or a
     * per-type repeat of the raw search alone would still leave it re-run per type.
     */
    private val trackSearchMemo = CallMemo<TrackQuery, TrackSearchResult>()

    private suspend fun memoizedTrackSearch(request: EnrichmentRequest.ForTrack): TrackSearchResult =
        trackSearchMemo.get(trackQuery(request)) { searchTrack(request) }

    private suspend fun searchTrack(request: EnrichmentRequest.ForTrack): TrackSearchResult {
        val recordings = api.searchCanonicalRecordings(request.title, request.artist, request.album)
        val direct = pickBestRecording(request.title, recordings, request.album, request.artist)
        val resolved = direct ?: resolveTrackQualifierFallback(request.title, request.artist, request.album)
        return TrackSearchResult(resolved, viaQualifierFallback = direct == null && resolved != null)
    }

    /**
     * The pool a track miss *suggests* from, keyed as [trackSearchMemo] is.
     *
     * Unfiltered, and never what a request resolves out of: a suggestion list is a choose-a-version
     * surface, built the way [MusicBrainzProvider.searchCandidates] builds its own, because a list
     * narrowed to canonical recordings cannot answer "I want the Moscow one" — and the resolution
     * pool is narrowed to exactly that. A different query from [trackSearchMemo]'s canonical one —
     * but the plain recording search it fires can be byte-identical to what
     * [MusicBrainzApi.searchCanonicalRecordings] itself sends for a hint-less request: its fallback
     * when the canonical pool comes back empty, or the whole of the hint-less path when a trailing
     * qualifier group routes it straight to the plain search instead. Either way, what keeps that
     * pair to one upstream request is a memo inside [MusicBrainzApi], not this one.
     */
    private val trackSuggestionMemo = CallMemo<TrackQuery, List<MusicBrainzRecording>>()

    private suspend fun memoizedTrackSuggestions(request: EnrichmentRequest.ForTrack): List<MusicBrainzRecording> =
        trackSuggestionMemo.get(trackQuery(request)) {
            api.searchRecordings(request.title, request.artist, request.album)
        }

    /**
     * Near-miss suggestions for a track no pool holds, keyed as [trackSuggestionMemo] is and
     * memoized for the reason `MusicBrainzAlbumEnrichment.albumFuzzyMemo` is: the pool that decides
     * they are needed is memoized, so an absent track would otherwise pay a full `recording?query=`
     * per type for the same three suggestions.
     */
    private val trackFuzzyMemo = CallMemo<TrackQuery, List<MusicBrainzRecording>>()

    private suspend fun memoizedFuzzyRecordings(request: EnrichmentRequest.ForTrack): List<MusicBrainzRecording> =
        trackFuzzyMemo.get(trackQuery(request)) {
            api.searchRecordingsFuzzy(request.title, request.artist, MAX_SUGGESTIONS)
        }

    /**
     * [trackSearchMemo]'s key, in fields for the reason `MusicBrainzAlbumEnrichment.AlbumQuery` is.
     * The album hint is held as the search sends it, and a blank album is no album:
     * [MusicBrainzApi] narrows on the hint only when it is non-blank, so a request carrying `null`,
     * `""` or `" "` sends one query and must reach one key.
     */
    private data class TrackQuery(val title: String, val artist: String, val album: String?)

    private fun trackQuery(request: EnrichmentRequest.ForTrack) =
        TrackQuery(request.title, request.artist, request.album?.takeIf { it.isNotBlank() })

    /**
     * Same qualifier-fallback candidate search as
     * `MusicBrainzAlbumEnrichment.resolveAlbumQualifierFallback` — requires the same
     * "authoritative" hit (score floor, normalized title equality, matching credited artist; score
     * alone is not proof of identity) before ranking survivors — but for recordings, reuses
     * [pickBestRecording]'s existing ranking on the authoritative pool rather than introducing a
     * second, parallel tie-break primitive: recordings already have a tie-break shaped for their
     * own signals (video flag, official-album release, blank disambiguation), which the generic
     * kind/year tag tie-break would not improve on.
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

    /**
     * [year]/[country]/[releaseType] are null because a recording search hit carries none of its
     * own — those live on its releases, and picking "the" release needs a lookup this class never
     * does. [thumbnailUrl] is null because no search response can tell a real cover from a CAA 404.
     */
    private fun MusicBrainzRecording.toCandidate() = SearchCandidate(
        title = title, artist = artistCredit, year = null,
        country = null, releaseType = null, matchScore = ConfidenceCalculator.searchScore(score),
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id, musicBrainzReleaseGroupId = artReleaseGroupId),
        disambiguation = disambiguation,
    )
}
