package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.CallMemo
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.TransientIdentifierMarker
import com.landofoz.musicmeta.engine.namesNoEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Resolves the album a request names or identifies, and answers every album type off it — including
 * the ladder a title that no direct search resolves falls down.
 *
 * Holds one call's album memos, so the invariant is [MusicBrainzEnricher]'s: one
 * instance per call, and nothing memoized here outlives it.
 */
internal class MusicBrainzAlbumEnrichment(
    private val api: MusicBrainzApi,
    private val providerId: String,
    private val minMatchScore: Int,
) {

    /**
     * Release lookups by MBID, same shape as `MusicBrainzArtistEnrichment.artistMemo`.
     * One album is looked up more than once per `enrich()`: GENRE resolves it as identity and again
     * in the fan-out the identity MBID enables, and ALBUM_TRACKS wants the same response a third
     * time.
     */
    private val releaseMemo = CallMemo<String, MusicBrainzLookup<MusicBrainzRelease>>()

    internal suspend fun memoizedRelease(mbid: String): MusicBrainzLookup<MusicBrainzRelease> =
        releaseMemo.get(mbid) { api.lookupRelease(mbid) }

    /**
     * Release-group Wikidata/Wikipedia relations by release-group MBID, same shape as
     * [releaseMemo]. A release search never embeds these (they live on the release-group, not the
     * release), so this is a miss on the first type resolved for an album and a hit for every other
     * type in the same enrichment — same amortized cost as the artist bio path's `needsRelations`
     * lookup.
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
        if (type == EnrichmentType.RELEASE_EDITIONS) return enrichAlbumEditions(api, providerId, request)
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            enrichAlbumByMbid(request, mbid, type)?.let { return it }
        }
        if (namesNoEntity(request)) return EnrichmentResult.NotFound(type, providerId)
        val search = memoizedAlbumSearch(request.title, request.artist)
        val best = search.release ?: return notFoundWithSuggestions(
            type, providerId, search.originalPool,
            fuzzy = { memoizedFuzzyReleases(request.title, request.artist) },
        ) { it.toCandidate() }
        // A search hit carries tags only when its release group happens to have them; the release
        // lookup is what fills them. GENRE is the one type that reads them and the one this path
        // reaches — LABEL is answered from the identity payload and never gets here.
        val resolved = if (type == EnrichmentType.GENRE && best.tags.isEmpty()) {
            memoizedRelease(best.id).valueOrNull() ?: best
        } else {
            best
        }
        return buildAlbumResult(
            resolved, type, ConfidenceCalculator.searchScore(best.score), search.provenance(request.title),
        )
    }

    /** The truthful self-report [buildAlbumResult] carries, against the title the caller asked for. */
    private fun AlbumSearchResult.provenance(requestedTitle: String): LookupProvenance? =
        searchProvenance(viaQualifierFallback, requestedTitle, release?.title)

    /** The result the caller's identifier answers with, or null to resolve by name — see [suppliedRelease]. */
    private suspend fun enrichAlbumByMbid(
        request: EnrichmentRequest.ForAlbum,
        mbid: String,
        type: EnrichmentType,
    ): EnrichmentResult? = when (val lookup = suppliedRelease(request, mbid)) {
        is MusicBrainzLookup.Found -> {
            offerNames(lookup.value.title, lookup.value.artistCredit)
            buildAlbumResult(
                lookup.value, type, ConfidenceCalculator.idBasedLookup(),
                LookupProvenance.CANONICAL_ID,
            )
        }
        MusicBrainzLookup.Unreadable -> EnrichmentResult.NotFound(type, providerId)
        MusicBrainzLookup.Absent -> null
    }

    internal suspend fun enrichAlbumTracks(
        request: EnrichmentRequest.ForAlbum,
    ): EnrichmentResult {
        val type = EnrichmentType.ALBUM_TRACKS
        val mbid = request.identifiers.musicBrainzId
        // An identifier MusicBrainz holds nothing under falls through to the name search, as it does
        // in enrichAlbum; a release it holds whose body will not parse does not (see
        // [MusicBrainzLookup]), so the search is only reached when there is no release to be lost.
        val lookup = mbid?.let { suppliedRelease(request, it) }
        // Null for the identifier route, which reports no name route of its own and leaves the
        // engine to classify what the chain actually required.
        var searchRoute: LookupProvenance? = null
        val release = when (lookup) {
            is MusicBrainzLookup.Found -> lookup.value
            MusicBrainzLookup.Unreadable -> return EnrichmentResult.NotFound(type, providerId)
            MusicBrainzLookup.Absent, null -> {
                if (namesNoEntity(request)) return EnrichmentResult.NotFound(type, providerId)
                val search = memoizedAlbumSearch(request.title, request.artist)
                val searched = search.release?.id ?: return EnrichmentResult.NotFound(type, providerId)
                searchRoute = search.provenance(request.title)
                memoizedRelease(searched).valueOrNull()
                    ?: return EnrichmentResult.NotFound(type, providerId)
            }
        }
        if (release.tracks.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type, data = MusicBrainzMapper.toTracklist(release.tracks),
            provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release),
            provenance = searchRoute,
        )
    }

    /** [memoizedRelease], with the evidence the caller supplied beside the identifier checked against it. */
    private suspend fun suppliedRelease(
        request: EnrichmentRequest.ForAlbum,
        mbid: String,
    ): MusicBrainzLookup<MusicBrainzRelease> =
        memoizedRelease(mbid)
            .unlessDifferentArtist(request.artist, creditOf = { it.artistCredit.orEmpty() })
            .unlessPredatingFirstRelease(request.year)

    private suspend fun buildAlbumResult(
        release: MusicBrainzRelease,
        type: EnrichmentType,
        confidence: Float,
        provenance: LookupProvenance? = null,
    ): EnrichmentResult.Success {
        val (wikidataId, wikipediaTitle) = resolveReleaseGroupWikiLinks(release.releaseGroupId)
        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toAlbumMetadata(release),
            provider = providerId,
            confidence = confidence,
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release, wikidataId, wikipediaTitle),
            provenance = provenance,
        )
    }

    /**
     * Best-effort: [type] here is whatever the caller actually asked for (GENRE, LABEL, …), never
     * `ALBUM_DESCRIPTION` — MusicBrainz has no capability for it, so this side lookup only ever
     * runs as a byproduct of resolving a different type's *own*, already-successful data. A
     * transient failure here must not fail that unrelated type: it would turn a legitimate
     * `Success` into an `Error`, record a MusicBrainz breaker failure for a hiccup that had nothing
     * to do with the type being resolved, and — since identity resolution still fans out to every
     * requested type on an `Error` (`DefaultEnrichmentEngine`) — drop the resolved identifiers the
     * *rest* of that run's types would otherwise have used. So this degrades to "unresolved this
     * call" instead of propagating, mirroring [bodyOrThrowTransient]'s own split (null only for a
     * genuine [com.landofoz.musicmeta.http.HttpResult.ClientError] absence) one level up.
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

    /**
     * [searchAlbum]'s answer. [viaQualifierFallback] is true exactly when [release] was reached by
     * searching a [MusicBrainzQualifierFallback] stripped candidate rather than the caller's
     * literal title — the truthful signal [enrichAlbum] and [enrichAlbumTracks] self-report
     * [com.landofoz.musicmeta.LookupProvenance.QUALIFIER_FALLBACK_NAME] from. Never true for the
     * symbol-folding last resort, whose hit is classified by the same title comparison as the
     * direct path rather than by the route that found it.
     */
    private data class AlbumSearchResult(
        val release: MusicBrainzRelease?,
        val originalPool: List<MusicBrainzRelease>,
        val viaQualifierFallback: Boolean = false,
    )

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

    private suspend fun memoizedAlbumSearch(title: String, artist: String): AlbumSearchResult =
        albumSearchMemo.get(AlbumQuery(title, artist)) { searchAlbum(title, artist) }

    /**
     * Near-miss suggestions for an album title nothing strict resolves, keyed as [albumSearchMemo]
     * is. [notFoundWithSuggestions] asks for these whenever the strict pool is empty, which — for
     * an album MusicBrainz does not hold — is once per album type of the request. The pool that
     * decides they are needed is memoized, so this has to be as well, or an absent album pays a
     * full `release?query=` per type for the same three suggestions.
     */
    private val albumFuzzyMemo = CallMemo<AlbumQuery, List<MusicBrainzRelease>>()

    private suspend fun memoizedFuzzyReleases(title: String, artist: String): List<MusicBrainzRelease> =
        albumFuzzyMemo.get(AlbumQuery(title, artist)) {
            api.searchReleasesFuzzy(title, artist, MAX_SUGGESTIONS)
        }

    /**
     * [albumSearchMemo] and [albumFuzzyMemo]'s key: the title and artist searched for. Two fields
     * rather than one joined string, because a joined key would need a separator no title can
     * contain.
     */
    private data class AlbumQuery(val title: String, val artist: String)

    /**
     * Resolves an album search, trying [title]/[artist] as-is first, and only falling back to
     * [MusicBrainzQualifierFallback]'s progressively-stripped
     * candidates when the direct search finds nothing at or above [minMatchScore], then to
     * [resolveAlbumSymbolFallback] — but only on an *empty* pool, and only when
     * [MusicBrainzTitleFolding.foldMatchPossible] holds: a populated pool that merely missed the
     * score floor means the title is searchable and the album is not there, and a title no folding
     * can rescue (a plain typo, say) makes the fallback's browse a certain miss either way, so in
     * both cases its extra calls would buy nothing.
     *
     * A blank [artist] never reaches [MusicBrainzReleaseRanking.pickBestRelease]. Its artist tier
     * has nothing to compare against and goes inert, leaving score, edition and year to crown a
     * winner no candidate is known to be — a guess carrying a resolved album's confidence. This
     * returns the pool unranked instead, which [enrichAlbum] hands to `notFoundWithSuggestions` as
     * candidates the caller can choose between. The qualifier and symbol fallbacks are skipped for
     * the same reason: they search on the same blank artist, so they would buy another guess at
     * the price of more requests.
     */
    private suspend fun searchAlbum(title: String, artist: String): AlbumSearchResult {
        val releases = api.searchReleases(title, artist)
        if (artist.isBlank()) return AlbumSearchResult(release = null, originalPool = releases)
        val direct = MusicBrainzReleaseRanking.pickBestRelease(releases, minMatchScore, artist = artist)
        val viaQualifier = direct == null
        val qualifierFallback = if (viaQualifier) resolveAlbumQualifierFallback(title, artist) else null
        val symbolWorthTrying = releases.isEmpty() && MusicBrainzTitleFolding.foldMatchPossible(title)
        val resolved = direct
            ?: qualifierFallback
            ?: if (symbolWorthTrying) resolveAlbumSymbolFallback(title, artist) else null
        return AlbumSearchResult(resolved, releases, viaQualifierFallback = viaQualifier && qualifierFallback != null)
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
     * — stricter than [MusicBrainzArtistEnrichment.enrichArtist], because a near-miss would scope
     * the browse to the wrong catalogue and nothing downstream would catch it.
     */
    private suspend fun resolveArtistMbidForFallback(artist: String, artistNorm: String): String? {
        val best = pickBestArtist(artist, api.searchArtists(artist)) ?: return null
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

    private fun MusicBrainzRelease.toCandidate() = SearchCandidate(
        title = title, artist = artistCredit, year = date?.take(4)?.toIntOrNull(),
        country = country, releaseType = releaseType, matchScore = ConfidenceCalculator.searchScore(score),
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id, musicBrainzReleaseGroupId = releaseGroupId),
        disambiguation = disambiguation,
    )

    companion object {
        /**
         * Browse pages [findReleaseGroupByFoldedTitle] reads before giving up. One is not enough:
         * Godspeed You! Black Emperor has 107 album/EP/single release groups (live, 2026-08-10).
         */
        internal const val SYMBOL_FALLBACK_MAX_PAGES = 3
    }
}
