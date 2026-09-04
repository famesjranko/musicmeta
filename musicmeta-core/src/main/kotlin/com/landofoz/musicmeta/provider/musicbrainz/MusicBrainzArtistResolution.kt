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
import com.landofoz.musicmeta.engine.NameMatchTier
import com.landofoz.musicmeta.engine.TransientIdentifierMarker
import com.landofoz.musicmeta.engine.nameMatchTier
import com.landofoz.musicmeta.engine.namesNoEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** New artist types routed through [MusicBrainzArtistResolution.enrichArtistNewType]. */
internal val ARTIST_NEW_TYPES = setOf(
    EnrichmentType.BAND_MEMBERS,
    EnrichmentType.ARTIST_DISCOGRAPHY,
    EnrichmentType.ARTIST_LINKS,
    EnrichmentType.ARTIST_POPULARITY,
)

/**
 * Resolves the artist a request names or identifies, and answers every artist type off it.
 *
 * Holds one call's artist memos, so the invariant is [MusicBrainzEnricher]'s: one
 * instance per call, and nothing memoized here outlives it.
 */
internal class MusicBrainzArtistResolution(
    private val api: MusicBrainzApi,
    private val providerId: String,
    private val minMatchScore: Int,
) {

    /**
     * Artist lookups by MBID: BAND_MEMBERS, ARTIST_LINKS and GENRE all want the same artist.
     *
     * A miss is held like any other answer, which is what [MusicBrainzLookup]'s three states are
     * for: an identifier MusicBrainz does not hold otherwise costs one lookup per type to be told
     * the same thing each time.
     */
    private val artistMemo = CallMemo<String, MusicBrainzLookup<MusicBrainzArtist>>()

    internal suspend fun memoizedArtist(mbid: String): MusicBrainzLookup<MusicBrainzArtist> =
        artistMemo.get(mbid) { api.lookupArtistWithRels(mbid) }

    /**
     * The artist pool a name resolves out of. Keyed on the name searched for, as
     * [MusicBrainzAlbumResolution.albumSearchMemo]'s key is: [enrichArtist] and
     * [enrichArtistNewType] (via [nameResolvedArtistId]) both search it for a request carrying no
     * MBID, so GENRE, BAND_MEMBERS, ARTIST_DISCOGRAPHY and ARTIST_LINKS of one request otherwise
     * repeat the same search once each.
     *
     * Holds *which* artist a name resolves to, not just a payload — safe for the reason
     * [MusicBrainzAlbumResolution.albumSearchMemo] is: nothing here outlives the call.
     */
    private val artistSearchMemo = CallMemo<String, List<MusicBrainzArtist>>()

    private suspend fun memoizedArtistSearch(name: String): List<MusicBrainzArtist> =
        artistSearchMemo.get(name) { api.searchArtists(name) }

    /**
     * Near-miss suggestions for an artist name nothing strict resolves, keyed as [artistSearchMemo]
     * is and memoized for the reason [MusicBrainzAlbumResolution.albumFuzzyMemo] is: the pool that
     * decides they are needed is memoized, so an absent artist would otherwise pay a full
     * `artist?query=` per type for the same three suggestions.
     */
    private val artistFuzzyMemo = CallMemo<String, List<MusicBrainzArtist>>()

    private suspend fun memoizedFuzzyArtists(name: String): List<MusicBrainzArtist> =
        artistFuzzyMemo.get(name) {
            api.searchArtistsFuzzy(name, MAX_SUGGESTIONS)
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
            when (val lookup = suppliedArtist(request, mbid)) {
                is MusicBrainzLookup.Found -> {
                    offerNames(lookup.value.name, null)
                    return buildArtistResult(
                        lookup.value, type, ConfidenceCalculator.idBasedLookup(),
                        LookupProvenance.CANONICAL_ID,
                    )
                }
                MusicBrainzLookup.Unreadable -> return EnrichmentResult.NotFound(type, providerId)
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
                type, providerId, artists,
                fuzzy = { memoizedFuzzyArtists(request.name) },
            ) { it.toCandidate() }
        }

        // Search results have metadata (genres, country) but lack URL relations
        // (wikidata, wikipedia). Do the full lookup when these are missing so
        // downstream providers (Wikidata, Wikipedia) can use them.
        val needsRelations = best.wikidataId == null && best.wikipediaTitle == null
        val resolved = if (needsRelations) resolveArtistRelations(best) else best

        // EXACT_NAME requires the artist's own canonical name; pickBestArtist can still win on an
        // alias or no-name-match tier, and that evidence must report FUZZY_NAME or it overstates.
        val provenance = if (artistNameTier(request.name, best) == NameMatchTier.CANONICAL) {
            LookupProvenance.EXACT_NAME
        } else {
            LookupProvenance.FUZZY_NAME
        }
        return buildArtistResult(resolved, type, artistMatchConfidence(request.name, best), provenance)
    }

    /**
     * Best-effort, mirroring [MusicBrainzAlbumResolution.resolveReleaseGroupWikiLinks]'s shape: a
     * transient on the full-artist lookup must not fail the type being resolved (GENRE, LABEL, …)
     * just because it also happens to carry wikidata/wikipedia relations as a byproduct — it
     * degrades to [best] (the search hit, already a valid [MusicBrainzArtist]) instead of
     * propagating.
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
                val browseId = discographyArtistId(request, mbid)
                    ?: return EnrichmentResult.NotFound(type, providerId)
                val groups = api.browseReleaseGroups(browseId)
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
     * full [com.landofoz.musicmeta.engine.EnrichmentEngine.enrich] it never sees the dead
     * identifier anyway, because identity resolution has already replaced it with the one it
     * resolved.
     */
    private suspend fun lookedUpOrNameResolvedArtist(
        request: EnrichmentRequest.ForArtist,
        mbid: String,
    ): MusicBrainzArtist? = when (val lookup = suppliedArtist(request, mbid)) {
        is MusicBrainzLookup.Found -> lookup.value
        MusicBrainzLookup.Unreadable -> null
        MusicBrainzLookup.Absent ->
            nameResolvedArtistId(request)?.let { memoizedArtist(it).valueOrNull() }
    }

    /** [memoizedArtist], with the caller's own name checked against it — see [unlessDifferentArtist]. */
    private suspend fun suppliedArtist(
        request: EnrichmentRequest.ForArtist,
        mbid: String,
    ): MusicBrainzLookup<MusicBrainzArtist> =
        memoizedArtist(mbid).unlessDifferentArtist(request.name, { it.name }) { it.alternativeNames() }

    /**
     * The artist id to browse a discography under, or null where none can be trusted.
     *
     * A browse learns nothing about who it browsed, so this is the one artist type that would
     * otherwise hand back another artist's entire catalogue as the caller's without ever noticing.
     * The check costs an artist lookup, and only on a request that supplied its own identifier — it
     * is memoized, so any other artist type in the same call has already paid for it.
     */
    private suspend fun discographyArtistId(
        request: EnrichmentRequest.ForArtist,
        mbid: String,
    ): String? = when {
        request.identifiers.musicBrainzId != mbid -> mbid
        suppliedArtist(request, mbid) !is MusicBrainzLookup.Absent -> mbid
        else -> nameResolvedArtistId(request)
    }

    /** The artist id [request]'s name resolves to, above [minMatchScore]. Null if no name matches. */
    private suspend fun nameResolvedArtistId(request: EnrichmentRequest.ForArtist): String? =
        if (namesNoEntity(request)) null
        else pickBestArtist(request.name, memoizedArtistSearch(request.name))
            ?.takeIf { it.score >= minMatchScore }
            ?.id

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

    private fun MusicBrainzArtist.toCandidate() = SearchCandidate(
        title = name, artist = null, year = beginDate?.take(4)?.toIntOrNull(),
        country = country, releaseType = type, matchScore = ConfidenceCalculator.searchScore(score),
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id),
        disambiguation = disambiguation,
    )
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
internal fun pickBestArtist(
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
internal fun artistMatchConfidence(query: String, artist: MusicBrainzArtist): Float =
    ConfidenceCalculator.searchScore(artist.score) * artistNameTier(query, artist).confidenceFactor

/**
 * How [query] matched [artist]: its own name, a name it is published under, or one of the search
 * hints MusicBrainz also stores. A "Search hint" alias counts as the weakest tier whatever else
 * it carries — MusicBrainz keeps typo-catchers ("Coolplay") in the same array as localised names
 * ("コールドプレイ"), a hint may itself be locale-tagged, and only the second kind is a name the
 * artist actually goes by.
 */
internal fun artistNameTier(query: String, artist: MusicBrainzArtist): NameMatchTier =
    nameMatchTier(requested = query, canonical = artist.name, aliases = artist.alternativeNames())
