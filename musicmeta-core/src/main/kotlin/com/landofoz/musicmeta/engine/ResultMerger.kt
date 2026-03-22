package com.landofoz.musicmeta.engine

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
}
