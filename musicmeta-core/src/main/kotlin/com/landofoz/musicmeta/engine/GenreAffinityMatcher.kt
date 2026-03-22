package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreAffinity

/**
 * Pure-logic composite synthesizer that builds a [EnrichmentData.GenreDiscovery] from
 * the GENRE sub-type result. Looks up each input genre tag in a static taxonomy of
 * ~70 relationships across 12 genre families (see [GENRE_TAXONOMY]) and scores
 * neighbors by `inputConfidence * relationshipWeight`.
 *
 * Relationship weights: sibling=0.9, child=0.8, parent=0.7
 */
object GenreAffinityMatcher : CompositeSynthesizer {

    override val type: EnrichmentType = EnrichmentType.GENRE_DISCOVERY

    override val dependencies: Set<EnrichmentType> = setOf(EnrichmentType.GENRE)

    override fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult {
        val genreResult = resolved[EnrichmentType.GENRE]
        if (genreResult == null || genreResult is EnrichmentResult.NotFound) {
            return EnrichmentResult.NotFound(EnrichmentType.GENRE_DISCOVERY, "no_genre_data")
        }
        val metadata = (genreResult as? EnrichmentResult.Success)?.data as? EnrichmentData.Metadata
            ?: return EnrichmentResult.NotFound(EnrichmentType.GENRE_DISCOVERY, "no_genre_data")
        val genreTags = metadata.genreTags
        if (genreTags.isNullOrEmpty()) {
            return EnrichmentResult.NotFound(EnrichmentType.GENRE_DISCOVERY, "no_genre_tags")
        }

        val candidates = buildCandidates(genreTags)
        if (candidates.isEmpty()) {
            return EnrichmentResult.NotFound(EnrichmentType.GENRE_DISCOVERY, "no_genre_tags")
        }

        val deduplicated = deduplicateByName(candidates).sortedByDescending { it.affinity }
        return EnrichmentResult.Success(
            type = EnrichmentType.GENRE_DISCOVERY,
            data = EnrichmentData.GenreDiscovery(deduplicated),
            provider = "genre_affinity_matcher",
            confidence = ConfidenceCalculator.authoritative(),
        )
    }

    private fun buildCandidates(
        genreTags: List<com.landofoz.musicmeta.GenreTag>,
    ): List<GenreAffinity> {
        val candidates = mutableListOf<GenreAffinity>()
        for (tag in genreTags) {
            val normalizedKey = GenreMerger.normalize(tag.name)
            val entries = GENRE_TAXONOMY[normalizedKey] ?: continue
            for (entry in entries) {
                candidates.add(GenreAffinity(
                    name = entry.related,
                    affinity = tag.confidence * entry.weight,
                    relationship = entry.relationship,
                    sourceGenres = listOf(normalizedKey),
                ))
            }
        }
        return candidates
    }

    /** Deduplicates by name: keeps highest affinity; merges sourceGenres when affinity is equal. */
    private fun deduplicateByName(candidates: List<GenreAffinity>): Collection<GenreAffinity> {
        val byName = LinkedHashMap<String, GenreAffinity>()
        for (candidate in candidates) {
            val existing = byName[candidate.name]
            byName[candidate.name] = when {
                existing == null -> candidate
                candidate.affinity > existing.affinity -> candidate
                candidate.affinity == existing.affinity -> existing.copy(
                    sourceGenres = (existing.sourceGenres + candidate.sourceGenres).distinct(),
                )
                else -> existing
            }
        }
        return byName.values
    }
}
