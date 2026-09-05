package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SimilarArtist

/**
 * Deduplicates and merges similar artist results from multiple providers.
 * Additive scoring: artists recommended by multiple providers rank higher.
 *
 * The summed scores are rescaled against the merged list's own maximum rather than clamped, so a
 * [SimilarArtist.matchScore] is a position within one merged list and nothing else — it is not
 * comparable against another list's, nor against the figure a provider reported.
 */
internal object SimilarArtistMerger : ResultMerger {

    override val type: EnrichmentType = EnrichmentType.SIMILAR_ARTISTS

    /**
     * Merges multiple successful provider results for SIMILAR_ARTISTS into a single result.
     * Collects all SimilarArtist entries, deduplicates by normalized name, sums matchScores and
     * rescales them against the merged maximum, merges sources and identifiers, and sorts by
     * matchScore descending.
     * Returns NotFound if results is empty; returns the first result as-is if no artists present.
     */
    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val contributingResults = results.filter {
            (it.data as? EnrichmentData.SimilarArtists)?.artists?.isNotEmpty() == true
        }
        val allArtists = contributingResults.flatMap { (it.data as EnrichmentData.SimilarArtists).artists }
        if (allArtists.isEmpty()) return results.first()

        val merged = mergeArtists(allArtists)
        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.SimilarArtists(artists = merged),
            provider = "similar_artist_merger",
            confidence = results.maxOf { it.confidence },
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
            // See weakestProvenance's KDoc for why the merge takes the least-confident contributor.
            provenance = weakestProvenance(contributingResults.map { it.provenance ?: LookupProvenance.FUZZY_NAME }),
        )
    }

    /**
     * Merges a list of similar artists from multiple providers.
     *
     * - Deduplicates by normalized name (lowercase trim)
     * - Sums matchScores across providers, then divides every sum by the largest of them, so the
     *   top entry is 1.0 and the spacing below it survives. A list whose maximum is not positive
     *   is left as it is.
     * - Merges sources lists
     * - Merges identifiers: prefers MBID when available, combines extra maps
     * - Returns results sorted by matchScore descending
     */
    internal fun mergeArtists(artists: List<SimilarArtist>): List<SimilarArtist> {
        if (artists.isEmpty()) return emptyList()

        val summed = groupArtists(artists).map { group ->
            val first = group.first()
            val totalScore = group
                .map { it.matchScore }
                .fold(0f) { acc, s -> acc + s }
            val allSources = group.flatMap { it.sources }.distinct()
            val mergedIdentifiers = ResultMerger.mergeIdentifiers(group.map { it.identifiers })

            SimilarArtist(
                name = first.name,
                identifiers = mergedIdentifiers,
                matchScore = totalScore,
                sources = allSources,
            )
        }

        // A list whose maximum is not positive has no scale to divide by, so it passes through
        // untouched — every contributor scoring zero must stay at zero, not become NaN.
        val scale = summed.maxOfOrNull { it.matchScore }?.takeIf { it > 0f } ?: 1f
        return summed
            .map { it.copy(matchScore = it.matchScore / scale) }
            .sortedByDescending { it.matchScore }
    }

    /**
     * The alias pool an entity is published under, by the name an entry carries.
     *
     * A seam the probe fills offline. Production resolves one pool per call for the *requested*
     * artist; a pool per similar-artist entry is a lookup this merger has no way to make.
     */
    internal var aliasPoolLookup: (String) -> List<AlternativeName> = { emptyList() }

    /**
     * The entries of [artists] that are one act, in first-occurrence order.
     *
     * An entry carrying an MBID joins the group carrying that same MBID; failing that it joins a
     * same-name group, or a group whose alias pool publishes it, only while that group carries no
     * MBID of its own. An entry carrying no MBID takes the same two name paths without that guard.
     * Two entries whose MBIDs disagree therefore never land in one group, whatever a pool says.
     */
    internal fun groupArtists(artists: List<SimilarArtist>): List<List<SimilarArtist>> {
        val groups = mutableListOf<MutableList<SimilarArtist>>()
        val groupMbid = mutableListOf<String?>()
        val groupName = mutableListOf<String>()
        for (artist in artists) {
            val mbid = artist.identifiers.musicBrainzId
            val key = normalize(artist.name)
            val open = groupName.indices.filter { mbid == null || groupMbid[it] == null || groupMbid[it] == mbid }
            val index = if (mbid == null) -1 else groupMbid.indexOfFirst { it == mbid }
            val joined = index.takeIf { it >= 0 }
                ?: open.firstOrNull { normalize(groupName[it]) == key }
                ?: open.firstOrNull { onePoolHoldsBoth(groupName[it], artist.name) }
            if (joined == null) {
                groups += mutableListOf(artist)
                groupMbid += mbid
                groupName += artist.name
            } else {
                groups[joined] += artist
                if (groupMbid[joined] == null) groupMbid[joined] = mbid
            }
        }
        return groups
    }

    /** Whether either name's own alias pool publishes the other, at same name. */
    private fun onePoolHoldsBoth(one: String, other: String): Boolean =
        nameMatchTier(other, one, aliasPoolLookup(one)) != NameMatchTier.NONE ||
            nameMatchTier(one, other, aliasPoolLookup(other)) != NameMatchTier.NONE

    private fun normalize(name: String): String = name.trim().lowercase()
}
