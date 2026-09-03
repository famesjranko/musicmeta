package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Strategy for synthesizing a composite EnrichmentType from resolved sub-type results.
 * Used for types that depend on other types being resolved first.
 * ARTIST_TIMELINE is the first composite type; Phase 16 will add GENRE_DISCOVERY.
 */
public interface CompositeSynthesizer {
    /** The composite EnrichmentType this synthesizer produces. */
    public val type: EnrichmentType

    /**
     * The sub-types that must be resolved before this synthesizer can run.
     *
     * A dependency may itself be a composite: the engine resolves the graph transitively and
     * settles this type once its own dependencies are in, however many synthesizers deep they go.
     * A dependency the caller never requested is resolved anyway, and through the same path it
     * would take if requested directly — a dependency with a registered [ResultMerger] is merged
     * across every provider that answers, not taken from the first one.
     *
     * **Read once, when the engine is built.** Return a stable value: the engine snapshots this
     * graph at construction and schedules against the snapshot, so a value that changes between
     * reads is not honoured, it is ignored.
     *
     * **A cycle is refused.** If this type depends, directly or transitively, on itself,
     * [com.landofoz.musicmeta.EnrichmentEngine.Builder.build] throws `IllegalArgumentException`
     * naming every type on the cycle. There is no resolution order for a cycle, so the alternative
     * is types that silently never settle.
     */
    public val dependencies: Set<EnrichmentType>

    /**
     * Synthesizes a composite result from resolved sub-type results.
     *
     * Each dependency in [resolved] arrives finalized — catalog-filtered, provenance-stamped, and,
     * under [com.landofoz.musicmeta.cache.CacheMode.STALE_IF_ERROR], stale-cache-substituted — the
     * same form a caller's own [EnrichmentResults][com.landofoz.musicmeta.EnrichmentResults] would
     * show for that type, never the raw provider-chain result. A synthesizer that wants to react to
     * "this dependency genuinely failed" cannot distinguish that from "this dependency is stale":
     * both arrive as the substituted `Success`.
     *
     * @param resolved Map of resolved sub-type results (includes dependencies).
     * @param identityResult The identity resolution result, if available.
     * @param request The original enrichment request.
     */
    public fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult
}
