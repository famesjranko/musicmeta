package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.NameMatchTier
import com.landofoz.musicmeta.engine.ResolvedEntityNames
import com.landofoz.musicmeta.engine.TransientIdentifierMarker
import com.landofoz.musicmeta.engine.nameMatchTier
import com.landofoz.musicmeta.engine.namesNoEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

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

    /**
     * Artist lookups by MBID: BAND_MEMBERS, ARTIST_LINKS and GENRE all want the same artist.
     *
     * A miss is held like any other answer, which is what [MusicBrainzLookup]'s three states are
     * for: an identifier MusicBrainz does not hold otherwise costs one lookup per type to be told
     * the same thing each time.
     */
    private val artistMemo = CallMemo<String, MusicBrainzLookup<MusicBrainzArtist>>()

    private suspend fun memoizedArtist(mbid: String): MusicBrainzLookup<MusicBrainzArtist> =
        artistMemo.get(mbid) { api.lookupArtistWithRels(mbid) }

    /**
     * The artist pool a name resolves out of. Keyed on the normalized name, as [albumSearchMemo]'s
     * key is: [enrichArtist] and [enrichArtistNewType] (via [nameResolvedArtistId]) both search it
     * for a request carrying no MBID, so GENRE, BAND_MEMBERS, ARTIST_DISCOGRAPHY and ARTIST_LINKS of
     * one request otherwise repeat the same search once each.
     *
     * Holds *which* artist a name resolves to, not just a payload — safe for the reason
     * [albumSearchMemo] is: nothing here outlives the call.
     */
    private val artistSearchMemo = CallMemo<String, List<MusicBrainzArtist>>()

    private suspend fun memoizedArtistSearch(name: String): List<MusicBrainzArtist> =
        artistSearchMemo.get(MusicBrainzQualifierFallback.normalize(name)) { api.searchArtists(name) }

    /**
     * Near-miss suggestions for an artist name nothing strict resolves, keyed as [artistSearchMemo]
     * is and memoized for the reason [albumFuzzyMemo] is: the pool that decides they are needed is
     * memoized, so an absent artist would otherwise pay a full `artist?query=` per type for the same
     * three suggestions.
     */
    private val artistFuzzyMemo = CallMemo<String, List<MusicBrainzArtist>>()

    private suspend fun memoizedFuzzyArtists(name: String): List<MusicBrainzArtist> =
        artistFuzzyMemo.get(MusicBrainzQualifierFallback.normalize(name)) {
            api.searchArtistsFuzzy(name, MAX_SUGGESTIONS)
        }

    /**
     * Release lookups by MBID, same shape as [artistMemo].
     * One album is looked up more than once per `enrich()`: GENRE resolves it as identity and again
     * in the fan-out the identity MBID enables, and ALBUM_TRACKS wants the same response a third
     * time.
     */
    private val releaseMemo = CallMemo<String, MusicBrainzLookup<MusicBrainzRelease>>()

    private suspend fun memoizedRelease(mbid: String): MusicBrainzLookup<MusicBrainzRelease> =
        releaseMemo.get(mbid) { api.lookupRelease(mbid) }

    /**
     * The value a lookup resolved to, or null for either miss — for the call sites where an
     * identifier this call resolved *itself* is being looked up, so [MusicBrainzLookup.Absent]
     * cannot mean "the caller named something that does not exist" and both misses degrade the
     * same way.
     */
    private fun <T> MusicBrainzLookup<T>.valueOrNull(): T? = (this as? MusicBrainzLookup.Found)?.value

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

    /**
     * Raw recording lookups by MBID, same shape as [releaseMemo] — held raw because CREDITS and the
     * recording's own fields parse the same response two different ways
     * ([MusicBrainzApi.lookupRecording]), so one call serves every track type of a request that
     * carries an MBID.
     */
    private val recordingMemo = CallMemo<String, MusicBrainzLookup<JSONObject>>()

    private suspend fun memoizedRecording(mbid: String): MusicBrainzLookup<JSONObject> =
        recordingMemo.get(mbid) { api.lookupRecording(mbid) }

    /**
     * Recording ids [enrichTrack] resolved *by search* during this call.
     *
     * This is what tells a caller's MBID apart from the engine's own echo of one. Identity
     * resolution runs on this instance before the fan-out
     * ([com.landofoz.musicmeta.engine.DefaultEnrichmentEngine]) and merges the recording it picked
     * into the request, so every type then sees an MBID that was not there when the call started.
     * Looking *that* up would change which release-group answers a name-only request; looking up one
     * that came from outside the call is what this path exists to do. Nothing else can draw the line
     * — the request carries no provenance, and does not need to.
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
            when (val lookup = memoizedRelease(mbid)) {
                is MusicBrainzLookup.Found -> {
                    offerNames(lookup.value.title, lookup.value.artistCredit)
                    return buildAlbumResult(
                        lookup.value, type, ConfidenceCalculator.idBasedLookup(),
                        LookupProvenance.CANONICAL_ID,
                    )
                }
                MusicBrainzLookup.Unreadable -> return EnrichmentResult.NotFound(type, providerId)
                // Absent: the identifier names no release, so the request resolves by name below,
                // exactly as one carrying no identifier does. See [MusicBrainzLookup].
                MusicBrainzLookup.Absent -> Unit
            }
        }
        if (namesNoEntity(request)) return EnrichmentResult.NotFound(type, providerId)
        val search = memoizedAlbumSearch(request.title, request.artist)
        val best = search.release ?: return notFoundWithSuggestions(
            type, search.originalPool,
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
        return buildAlbumResult(resolved, type, ConfidenceCalculator.searchScore(best.score), search.provenance())
    }

    /** The truthful self-report [buildAlbumResult]/[trackResult] carry when a search hit did. */
    private fun AlbumSearchResult.provenance(): LookupProvenance? = qualifierFallbackProvenance(viaQualifierFallback)

    /** As [AlbumSearchResult.provenance], for [TrackSearchResult]. */
    private fun TrackSearchResult.provenance(): LookupProvenance? = qualifierFallbackProvenance(viaQualifierFallback)

    private fun qualifierFallbackProvenance(viaQualifierFallback: Boolean): LookupProvenance? =
        if (viaQualifierFallback) LookupProvenance.QUALIFIER_FALLBACK_NAME else null

    internal suspend fun enrichAlbumTracks(
        request: EnrichmentRequest.ForAlbum,
    ): EnrichmentResult {
        val type = EnrichmentType.ALBUM_TRACKS
        val mbid = request.identifiers.musicBrainzId
        // An identifier MusicBrainz holds nothing under falls through to the name search, as it does
        // in enrichAlbum; a release it holds whose body will not parse does not (see
        // [MusicBrainzLookup]), so the search is only reached when there is no release to be lost.
        val lookup = mbid?.let { memoizedRelease(it) }
        var viaQualifierFallback = false
        val release = when (lookup) {
            is MusicBrainzLookup.Found -> lookup.value
            MusicBrainzLookup.Unreadable -> return EnrichmentResult.NotFound(type, providerId)
            MusicBrainzLookup.Absent, null -> {
                if (namesNoEntity(request)) return EnrichmentResult.NotFound(type, providerId)
                val search = memoizedAlbumSearch(request.title, request.artist)
                val searched = search.release?.id ?: return EnrichmentResult.NotFound(type, providerId)
                viaQualifierFallback = search.viaQualifierFallback
                memoizedRelease(searched).valueOrNull()
                    ?: return EnrichmentResult.NotFound(type, providerId)
            }
        }
        if (release.tracks.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type, data = MusicBrainzMapper.toTracklist(release.tracks),
            provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release),
            provenance = qualifierFallbackProvenance(viaQualifierFallback),
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
            when (val lookup = memoizedArtist(mbid)) {
                is MusicBrainzLookup.Found -> {
                    offerNames(lookup.value.name, null)
                    return buildArtistResult(
                        lookup.value, type, ConfidenceCalculator.idBasedLookup(),
                        LookupProvenance.CANONICAL_ID,
                    )
                }
                MusicBrainzLookup.Unreadable -> return EnrichmentResult.NotFound(type, providerId)
                // Absent: the identifier names no artist, so the request resolves by name below,
                // exactly as one carrying no identifier does. See [MusicBrainzLookup].
                MusicBrainzLookup.Absent -> Unit
            }
        }

        if (namesNoEntity(request)) return EnrichmentResult.NotFound(type, providerId)
        val artists = memoizedArtistSearch(request.name)
        // An empty pool and a pool whose best is below the bar are one answer, not two: neither
        // names an artist to describe, and both offer the pool (or a fuzzy retry) to choose from.
        val best = pickBestArtist(request.name, artists)
        if (best == null || best.score < minMatchScore) {
            return notFoundWithSuggestions(
                type, artists,
                fuzzy = { memoizedFuzzyArtists(request.name) },
            ) { it.toCandidate() }
        }

        // Search results have metadata (genres, country) but lack URL relations
        // (wikidata, wikipedia). Do the full lookup when these are missing so
        // downstream providers (Wikidata, Wikipedia) can use them.
        val needsRelations = best.wikidataId == null && best.wikipediaTitle == null
        val resolved = if (needsRelations) resolveArtistRelations(best) else best

        return buildArtistResult(resolved, type, artistMatchConfidence(request.name, best))
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
        memoizedArtist(best.id).valueOrNull() ?: best
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
        val mbid = request.identifiers.musicBrainzId
            ?: nameResolvedArtistId(request)
            ?: return EnrichmentResult.NotFound(type, providerId)

        return when (type) {
            EnrichmentType.BAND_MEMBERS -> {
                val artist = lookedUpOrNameResolvedArtist(request, mbid)
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
            EnrichmentType.ARTIST_POPULARITY -> {
                // Rides the lookup the other artist types already make: `inc=ratings` is in
                // ARTIST_LOOKUP_INC, so this costs no extra request. No votes is a genuine absence.
                val artist = lookedUpOrNameResolvedArtist(request, mbid)
                    ?: return EnrichmentResult.NotFound(type, providerId)
                val popularity = MusicBrainzMapper.toPopularity(artist.rating)
                if (popularity.signals.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
                EnrichmentResult.Success(
                    type = type,
                    data = popularity,
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            EnrichmentType.ARTIST_LINKS -> {
                val artist = lookedUpOrNameResolvedArtist(request, mbid)
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

    /**
     * The artist [mbid] names, or — when MusicBrainz holds nothing under it — the one the name
     * search picks instead, as [enrichArtist] resolves the same case. Null is a genuine miss.
     *
     * [MusicBrainzLookup.Unreadable] is not a miss to recover from: MusicBrainz holds that artist,
     * so another cannot stand in for it.
     *
     * `ARTIST_DISCOGRAPHY` needs no equivalent. It browses rather than looks up, so it has nothing
     * to learn an absence from without paying for a lookup it does not otherwise want — and in a
     * full [com.landofoz.musicmeta.engine.EnrichmentEngine.enrich] it never sees the dead identifier
     * anyway, because identity resolution has already replaced it with the one it resolved.
     */
    private suspend fun lookedUpOrNameResolvedArtist(
        request: EnrichmentRequest.ForArtist,
        mbid: String,
    ): MusicBrainzArtist? = when (val lookup = memoizedArtist(mbid)) {
        is MusicBrainzLookup.Found -> lookup.value
        MusicBrainzLookup.Unreadable -> null
        MusicBrainzLookup.Absent ->
            nameResolvedArtistId(request)?.let { memoizedArtist(it).valueOrNull() }
    }

    /** The artist id [request]'s name resolves to, above [minMatchScore]. Null if no name matches. */
    private suspend fun nameResolvedArtistId(request: EnrichmentRequest.ForArtist): String? =
        if (namesNoEntity(request)) null
        else pickBestArtist(request.name, memoizedArtistSearch(request.name))
            ?.takeIf { it.score >= minMatchScore }
            ?.id

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
     * so it names the recording the answer must describe, exactly as [enrichAlbum] and [enrichArtist]
     * treat theirs. A recording MusicBrainz *holds* is never traded for a search hit: answering with
     * a different recording is the defect this path exists to close, and that holds whether the
     * lookup's body parses or not.
     *
     * [MusicBrainzLookup.Absent] is the one case that is not a miss: MusicBrainz has stated it holds
     * no such recording under any entity type. An identifier naming nothing names no recording to be
     * faithful to, so there is no wrong-recording risk in resolving the request the way one carrying
     * no identifier at all resolves — and [enrichTrack] does exactly that with the null this returns
     * for it. Treating the two alike costs a consumer the whole track for a stale third-party id,
     * which is what these identifiers are in practice.
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
        val json = memoizedRecording(mbid).valueOrNull() ?: return null
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
        if (namesNoEntity(request)) return EnrichmentResult.NotFound(type, providerId)
        val search = memoizedTrackSearch(request)
        val best = search.recording ?: return trackMiss(request, type)

        rememberSearchResolved(best.id)
        return trackResult(best, type, ConfidenceCalculator.searchScore(best.score), search.provenance())
    }

    /**
     * The answer when a *name* resolves to no recording. Suggestions only — nothing offered here is
     * remembered by [rememberSearchResolved] or can become the answer.
     *
     * Reached from [enrichTrackBySearch] and nowhere else, which is load-bearing rather than
     * incidental: see [enrichTrackByMbid] for what these suggestions cost a request that reaches the
     * engine's identity resolution.
     *
     * [MusicBrainzApi.searchRecordings]' own hint-less retry re-sends the title quoted (see its
     * KDoc), so only the fuzzy search can rescue a typo, and only an empty suggestion pool asks for
     * it.
     */
    private suspend fun trackMiss(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult.NotFound = notFoundWithSuggestions(
        type, memoizedTrackSuggestions(request),
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
        val json = memoizedRecording(mbid).valueOrNull()
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
        provenance: LookupProvenance? = null,
    ): EnrichmentResult.Success = EnrichmentResult.Success(
        type = type,
        data = MusicBrainzMapper.toArtistMetadata(artist),
        provider = providerId,
        confidence = confidence,
        resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
        provenance = provenance,
    )

    /**
     * Hands the engine the names of the entity a caller's **identifier** named, for the request
     * fields a caller holding only that identifier left blank ([ResolvedEntityNames]). The artist is
     * MusicBrainz's `artist-credit` joined with its own join phrases — "Queen & David Bowie", not
     * "Queen" — which is the string a name-search provider is asked with.
     *
     * Offered from the three lookup paths and never from a search. A search hit is what a *name*
     * resolved to, so backfilling from one would rewrite the request with whatever an under-specified
     * query happened to rank first — for "Under Pressure" with no artist, a Joss Stone cover. The
     * caller's own names are the better answer in that case, and they are already on the request.
     */
    private suspend fun offerNames(title: String?, artist: String?) {
        currentCoroutineContext()[ResolvedEntityNames]?.offer(title, artist)
    }

    /**
     * What entity [mbid] names, or null when MusicBrainz holds it under none of the three.
     *
     * **Recording, then release, then artist**, at one lookup each: 1 request when it names a
     * recording, 2 for a release, 3 for an artist and 3 when it names nothing. Recording leads
     * because that is where third-party identifiers overwhelmingly come from. Each probe reuses the
     * memo the enricher's own lookups fill, so discovery inside an `enrich()` that already looked
     * the entity up costs nothing, and a miss is paid for once per call however many types ask.
     *
     * A transient propagates rather than reading as "no such entity" — every lookup here throws it
     * through `bodyOrThrowTransient`, so absence is only ever MusicBrainz's own 404.
     */
    internal suspend fun discoverEntityType(mbid: String): MusicBrainzEntityType? = when {
        memoizedRecording(mbid) !is MusicBrainzLookup.Absent -> MusicBrainzEntityType.RECORDING
        memoizedRelease(mbid) !is MusicBrainzLookup.Absent -> MusicBrainzEntityType.RELEASE
        memoizedArtist(mbid) !is MusicBrainzLookup.Absent -> MusicBrainzEntityType.ARTIST
        else -> null
    }

    /**
     * Rank artist candidates on how the requested name matched first, then on whether the candidate
     * carries tags, then on MusicBrainz's own order.
     *
     * The name tier leads because [MusicBrainzApi.searchArtists] searches aliases as well as names,
     * so the pool now holds candidates matched on a name the artist merely *also* goes by — and a
     * canonical hit must always beat one of those, however well tagged. Within a tier the tags check
     * survives unchanged: it separates the real entity from the near-empty duplicate MusicBrainz also
     * holds under that name.
     *
     * Null for an empty pool: a name MusicBrainz knows nothing of is a miss, not a failure.
     */
    private fun pickBestArtist(
        query: String,
        candidates: List<MusicBrainzArtist>,
    ): MusicBrainzArtist? = candidates.maxWithOrNull(
        compareBy({ -artistNameTier(query, it).ordinal }, { it.tags.isNotEmpty() }),
    )

    /**
     * MusicBrainz's own relevance score for [artist], scaled by how [query] reached it.
     *
     * A search that matches aliases resolves artists a canonical-name-only search would miss, and a
     * caller has to be able to tell those apart from a canonical-name hit — `identityMatchScore` is
     * that signal, and
     * it would say nothing if an alias hit and a name hit both scored 100. Still identification and
     * not payload (`docs/pitfalls.md` §8): the tier says how sure we are this is the right entity,
     * never how much it carried.
     */
    private fun artistMatchConfidence(query: String, artist: MusicBrainzArtist): Float =
        ConfidenceCalculator.searchScore(artist.score) * artistNameTier(query, artist).confidenceFactor

    /**
     * How [query] matched [artist]: its own name, a name it is published under, or one of the search
     * hints MusicBrainz also stores. A "Search hint" alias counts as the weakest tier whatever else
     * it carries — MusicBrainz keeps typo-catchers ("Coolplay") in the same array as localised names
     * ("コールドプレイ"), a hint may itself be locale-tagged, and only the second kind is a name the
     * artist actually goes by.
     */
    private fun artistNameTier(query: String, artist: MusicBrainzArtist): NameMatchTier =
        nameMatchTier(
            requested = query,
            canonical = artist.name,
            aliases = artist.aliases.map {
                val isSearchHint = it.type.equals(SEARCH_HINT_ALIAS_TYPE, ignoreCase = true)
                AlternativeName(name = it.name, official = !isSearchHint && (it.primary || it.locale != null))
            },
        )

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

    /**
     * [searchAlbum]'s answer. [viaQualifierFallback] is true exactly when [release] was reached by
     * searching a [MusicBrainzQualifierFallback] stripped candidate rather than the caller's literal
     * title — the truthful signal [enrichAlbum] and [enrichAlbumTracks] self-report
     * [com.landofoz.musicmeta.LookupProvenance.QUALIFIER_FALLBACK_NAME] from. Never true for the
     * symbol-folding last resort, which is a different resolution route with its own evidence.
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
     * [searchTrack]'s answer. [viaQualifierFallback] is true exactly when [recording] was reached by
     * searching a [MusicBrainzQualifierFallback] stripped candidate rather than the caller's literal
     * title — the truthful signal [enrichTrackBySearch] self-reports
     * [com.landofoz.musicmeta.LookupProvenance.QUALIFIER_FALLBACK_NAME] from.
     */
    private data class TrackSearchResult(
        val recording: MusicBrainzRecording?,
        val viaQualifierFallback: Boolean = false,
    )

    /**
     * Track resolution by title/artist/album: [searchTrack]'s whole ladder, which every track type
     * of one request otherwise re-runs — the search plus, on an empty pool,
     * [resolveTrackQualifierFallback]'s own searches. Keyed like [albumSearchMemo], and holding
     * which recording a name resolves to under the same protection: nothing here outlives the call.
     *
     * A track repeats this search where an album does not. Identity resolution merges the recording
     * it picked into the request, and [enrichTrack] deliberately routes that MBID back to the search
     * rather than looking it up ([isOwnSearchEcho]), so identity's query and every type's query are
     * the same one. Two of them are not equivalent to one: MusicBrainz does not order identical
     * searches identically, and [pickBestRecording] keeps the first maximum among ties, so a second
     * search ranks a differently-ordered pool and can pick a different recording — leaving the
     * identity a consumer reads naming one recording while its payload describes another.
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
        val direct = pickBestRecording(request.title, recordings, request.album)
        val resolved = direct ?: resolveTrackQualifierFallback(request.title, request.artist, request.album)
        return TrackSearchResult(resolved, viaQualifierFallback = direct == null && resolved != null)
    }

    /**
     * The pool a track miss *suggests* from, keyed as [trackSearchMemo] is.
     *
     * Unfiltered, and never what a request resolves out of: a suggestion list is a choose-a-version
     * surface, built the way [MusicBrainzProvider.searchCandidates] builds its own, because a list
     * narrowed to canonical recordings cannot answer "I want the Moscow one" — and the resolution
     * pool is narrowed to exactly that. A different query from [trackSearchMemo]'s, so it holds its
     * own answer; one extra request, on the miss path only.
     */
    private val trackSuggestionMemo = CallMemo<TrackQuery, List<MusicBrainzRecording>>()

    private suspend fun memoizedTrackSuggestions(request: EnrichmentRequest.ForTrack): List<MusicBrainzRecording> =
        trackSuggestionMemo.get(trackQuery(request)) {
            api.searchRecordings(request.title, request.artist, request.album)
        }

    /**
     * Near-miss suggestions for a track no pool holds, keyed as [trackSuggestionMemo] is and
     * memoized for the reason [albumFuzzyMemo] is: the pool that decides they are needed is
     * memoized, so an absent track would otherwise pay a full `recording?query=` per type for the
     * same three suggestions.
     */
    private val trackFuzzyMemo = CallMemo<TrackQuery, List<MusicBrainzRecording>>()

    private suspend fun memoizedFuzzyRecordings(request: EnrichmentRequest.ForTrack): List<MusicBrainzRecording> =
        trackFuzzyMemo.get(trackQuery(request)) {
            api.searchRecordingsFuzzy(request.title, request.artist, MAX_SUGGESTIONS)
        }

    /** [trackSearchMemo]'s key, in fields for the reason [AlbumQuery] is. A blank album is no album. */
    private data class TrackQuery(val title: String, val artist: String, val album: String)

    private fun trackQuery(request: EnrichmentRequest.ForTrack) = TrackQuery(
        MusicBrainzQualifierFallback.normalize(request.title),
        MusicBrainzQualifierFallback.normalize(request.artist),
        MusicBrainzQualifierFallback.normalize(request.album),
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
        val viaQualifier = direct == null
        val qualifierFallback = if (viaQualifier) resolveAlbumQualifierFallback(title, artist) else null
        val resolved = direct
            ?: qualifierFallback
            ?: if (releases.isEmpty()) resolveAlbumSymbolFallback(title, artist) else null
        return AlbumSearchResult(resolved, releases, viaQualifierFallback = viaQualifier && qualifierFallback != null)
    }

    /**
     * Tries each of [MusicBrainzQualifierFallback]'s fallback candidates (dropping the original
     * title — the caller has already searched it) in most-specific-first order, stopping at the
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

        /** MusicBrainz's alias type for a name it stores for its own indexer, not for the artist. */
        private const val SEARCH_HINT_ALIAS_TYPE = "Search hint"

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
            EnrichmentType.ARTIST_POPULARITY,
        )
    }
}
