package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Strategy for synthesizing a composite EnrichmentType from resolved sub-type results.
 * Used for types that depend on other types being resolved first.
 * ARTIST_TIMELINE is the first composite type; Phase 16 will add GENRE_DISCOVERY.
 */
interface CompositeSynthesizer {
    /** The composite EnrichmentType this synthesizer produces. */
    val type: EnrichmentType

    /** The sub-types that must be resolved before this synthesizer can run. */
    val dependencies: Set<EnrichmentType>

    /**
     * Synthesizes a composite result from resolved sub-type results.
     * @param resolved Map of resolved sub-type results (includes dependencies).
     * @param identityResult The identity resolution result, if available.
     * @param request The original enrichment request.
     */
    fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult
}
