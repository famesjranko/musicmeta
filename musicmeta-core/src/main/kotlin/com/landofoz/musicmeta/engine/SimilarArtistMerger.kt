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

    /** How `n` is chosen for a contributor's `1 - rank/n` normalisation. See [mergeArtists]. */
    internal enum class RankNBasis { OWN_LENGTH, TRUNCATED_TO_COMMON }

    /** The common length contributors are truncated to under [RankNBasis.TRUNCATED_TO_COMMON] —
     * the length Last.fm and Deezer already return, so only a longer list (Labs) is affected. */
    internal const val COMMON_LENGTH = 20

    /**
     * Merges multiple successful provider results for SIMILAR_ARTISTS into a single result.
     * Deduplicates by normalized name, sums per-contributor rank-normalised scores (capped at
     * 1.0), merges sources and identifiers, and sorts by matchScore descending.
     * Returns NotFound if results is empty; returns the first result as-is if no artists present.
     */
    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val contributingResults = results.filter {
            (it.data as? EnrichmentData.SimilarArtists)?.artists?.isNotEmpty() == true
        }
        val perContributor = contributingResults.map { (it.data as EnrichmentData.SimilarArtists).artists }
        if (perContributor.all { it.isEmpty() }) return results.first()

        val merged = mergeArtists(perContributor)
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
     * Merges similar artists from multiple providers, given as one list per contributor.
     *
     * `mergeArtists` moved from a flattened `List<SimilarArtist>` to `List<List<SimilarArtist>>`
     * for this arm: rank normalisation needs each contributor's own list length and each entry's
     * own rank within it, a boundary the flattened list erased. `merge()` above supplies that
     * boundary from `contributingResults`.
     *
     * - Normalises each contributor's own list by rank: entry at 0-indexed position `i` of an
     *   `n`-long list (n per [rankNBasis]) scores `1 - i/n`, so a provider's own top pick always
     *   scores 1.0 regardless of that provider's native scale.
     * - Deduplicates by normalized name (lowercase trim) across the flattened, now-normalised list
     * - Sums the normalised scores across providers (capped at 1.0)
     * - Merges sources lists
     * - Merges identifiers: prefers MBID when available, combines extra maps
     * - Returns results sorted by matchScore descending
     */
    internal fun mergeArtists(
        providerArtists: List<List<SimilarArtist>>,
        rankNBasis: RankNBasis = RankNBasis.OWN_LENGTH,
    ): List<SimilarArtist> {
        val normalised = providerArtists.flatMap { list -> rankNormalise(list, rankNBasis) }
        if (normalised.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<SimilarArtist>>()
        for (artist in normalised) {
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

    /**
     * One contributor's list with each entry's `matchScore` replaced by its rank position, 0-indexed,
     * against `n`. An intra-provider duplicate name (two entries, the same name, different ids) is
     * not deduped here — it consumes two rank slots exactly as returned, and is folded into one entry
     * later by [mergeArtists]'s name-key grouping, the same as any other duplicate name would be.
     */
    private fun rankNormalise(list: List<SimilarArtist>, basis: RankNBasis): List<SimilarArtist> {
        if (list.isEmpty()) return list
        val truncated = when (basis) {
            RankNBasis.OWN_LENGTH -> list
            RankNBasis.TRUNCATED_TO_COMMON -> list.take(COMMON_LENGTH)
        }
        val n = truncated.size
        return truncated.mapIndexed { index, artist ->
            artist.copy(matchScore = 1f - index.toFloat() / n)
        }
    }

    private fun normalize(name: String): String = name.trim().lowercase()
}
