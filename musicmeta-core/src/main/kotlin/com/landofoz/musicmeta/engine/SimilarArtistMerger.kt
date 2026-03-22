package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SimilarArtist

/**
 * Deduplicates and merges similar artist results from multiple providers.
 * Additive scoring: artists recommended by multiple providers rank higher.
 */
object SimilarArtistMerger : ResultMerger {

    override val type: EnrichmentType = EnrichmentType.SIMILAR_ARTISTS

    /**
     * Merges multiple successful provider results for SIMILAR_ARTISTS into a single result.
     * Collects all SimilarArtist entries, deduplicates by normalized name, sums matchScores
     * (capped at 1.0), merges sources and identifiers, and sorts by matchScore descending.
     * Returns NotFound if results is empty; returns the first result as-is if no artists present.
     */
    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val allArtists = results.flatMap { result ->
            (result.data as? EnrichmentData.SimilarArtists)?.artists.orEmpty()
        }
        if (allArtists.isEmpty()) return results.first()

        val merged = mergeArtists(allArtists)
        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.SimilarArtists(artists = merged),
            provider = "similar_artist_merger",
            confidence = results.maxOf { it.confidence },
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
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
                val mergedIdentifiers = mergeIdentifiers(group.map { it.identifiers })

                SimilarArtist(
                    name = first.name,
                    identifiers = mergedIdentifiers,
                    matchScore = totalScore,
                    sources = allSources,
                )
            }
            .sortedByDescending { it.matchScore }
    }

    /** Merges identifiers from multiple providers. Prefers non-null MBID; combines extra maps. */
    private fun mergeIdentifiers(ids: List<EnrichmentIdentifiers>): EnrichmentIdentifiers {
        val mbid = ids.firstNotNullOfOrNull { it.musicBrainzId }
        val combinedExtra = ids.fold(emptyMap<String, String>()) { acc, id -> acc + id.extra }
        return EnrichmentIdentifiers(
            musicBrainzId = mbid,
            extra = combinedExtra,
        )
    }

    private fun normalize(name: String): String = name.trim().lowercase()
}
