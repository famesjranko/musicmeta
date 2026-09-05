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
     * Collects all SimilarArtist entries, groups them by [groupArtists], sums matchScores and
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
     * - Deduplicates by MusicBrainz id where one is present, and by normalized name otherwise —
     *   see [groupArtists]
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
     * The entries of [artists] that are one act, grouped in first-occurrence order.
     *
     * An entry carrying a MusicBrainz id joins the group carrying that same id, whatever either is
     * named; failing that it joins a same-name group only while that group carries no id of its
     * own, so two entries whose ids disagree can never share a group. An entry carrying no id falls
     * back to the name, joining the first group under it rather than a bucket of its own.
     *
     * A caller therefore sees two entries wherever MusicBrainz holds one act under two ids, since
     * nothing below the merge can tell that apart from two acts sharing a name — a duplicate entry
     * splits a score the caller can still add up, where a fused one is an entry no act ever earned.
     */
    internal fun groupArtists(artists: List<SimilarArtist>): List<List<SimilarArtist>> {
        val groups = mutableListOf<MutableList<SimilarArtist>>()
        val groupMbid = mutableListOf<String?>()
        val groupKey = mutableListOf<String>()
        for (artist in artists) {
            val mbid = artist.identifiers.musicBrainzId
            val key = normalize(artist.name)
            val index = if (mbid == null) {
                groupKey.indexOfFirst { it == key }
            } else {
                groupMbid.indexOfFirst { it == mbid }
                    .takeIf { it >= 0 }
                    ?: groupKey.indices.firstOrNull { groupKey[it] == key && groupMbid[it] == null }
                    ?: -1
            }
            if (index < 0) {
                groups += mutableListOf(artist)
                groupMbid += mbid
                groupKey += key
            } else {
                groups[index] += artist
                if (groupMbid[index] == null) groupMbid[index] = mbid
            }
        }
        return groups
    }

    private fun normalize(name: String): String = name.trim().lowercase()
}
