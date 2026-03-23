package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Strategy for merging multiple provider results for a single EnrichmentType.
 * Used for types where all providers are queried (not short-circuited) and
 * their results are combined. GENRE is the first mergeable type; Phase 13
 * will add SIMILAR_ARTISTS.
 */
interface ResultMerger {
    /** The EnrichmentType this merger handles. */
    val type: EnrichmentType

    /**
     * Merges multiple successful results into a single result.
     * Returns NotFound if the input list is empty.
     */
    fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult

    companion object {
        /** Merges identifiers from multiple providers, preserving all typed fields. */
        fun mergeIdentifiers(ids: List<EnrichmentIdentifiers>): EnrichmentIdentifiers {
            return EnrichmentIdentifiers(
                musicBrainzId = ids.firstNotNullOfOrNull { it.musicBrainzId },
                musicBrainzReleaseGroupId = ids.firstNotNullOfOrNull { it.musicBrainzReleaseGroupId },
                wikidataId = ids.firstNotNullOfOrNull { it.wikidataId },
                isrc = ids.firstNotNullOfOrNull { it.isrc },
                barcode = ids.firstNotNullOfOrNull { it.barcode },
                wikipediaTitle = ids.firstNotNullOfOrNull { it.wikipediaTitle },
                extra = ids.fold(emptyMap()) { acc, id -> acc + id.extra },
            )
        }
    }
}
