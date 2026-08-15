package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SimilarArtist

/**
 * Deduplicates and merges similar artist results from multiple providers.
 * Additive scoring: artists recommended by multiple providers rank higher.
 */
internal object SimilarArtistMerger : ResultMerger {

    override val type: EnrichmentType = EnrichmentType.SIMILAR_ARTISTS

    /**
     * Merges multiple successful provider results for SIMILAR_ARTISTS into a single result.
     * Collects all SimilarArtist entries, deduplicates by normalized name, sums matchScores
     * (capped at 1.0), merges sources and identifiers, and sorts by matchScore descending.
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
            // No single provider's route speaks for a merged result; the weakest contributing
            // route is the smallest truthful summary. A contributor built outside the engine's
            // pre-merge stamp (e.g. a direct unit-test fixture) falls back to FUZZY_NAME, the same
            // conservative default observedProvenance uses for a route it cannot otherwise place.
            provenance = weakestProvenance(contributingResults.map { it.provenance ?: LookupProvenance.FUZZY_NAME }),
        )
    }

    /**
     * Merges a list of similar artists from multiple providers.
     *
     * - Deduplicates by normalized name (lowercase trim)
     * - Sums matchScores across providers (capped at 1.0)
     * - Merges sources lists
     * - Merges identifiers: prefers MBID when available, combines extra maps
     * - Returns results sorted by matchScore descending
     */
    internal fun mergeArtists(artists: List<SimilarArtist>): List<SimilarArtist> {
        if (artists.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<SimilarArtist>>()
        for (artist in artists) {
            val key = normalize(artist.name)
            grouped.getOrPut(key) { mutableListOf() }.add(artist)
        }

        return grouped.values
            .map { group ->
                val first = group.first()
                val totalScore = group
                    .map { it.matchScore }
                    .fold(0f) { acc, s -> acc + s }
                    .coerceAtMost(1.0f)
                val allSources = group.flatMap { it.sources }.distinct()
                val mergedIdentifiers = ResultMerger.mergeIdentifiers(group.map { it.identifiers })

                SimilarArtist(
                    name = first.name,
                    identifiers = mergedIdentifiers,
                    matchScore = totalScore,
                    sources = allSources,
                )
            }
            .sortedByDescending { it.matchScore }
    }

    private fun normalize(name: String): String = name.trim().lowercase()
}
