package com.cascade.enrichment

/**
 * A source of enrichment data. Each provider wraps a single external API
 * (MusicBrainz, LRCLIB, etc.) and declares what types of data it can supply.
 *
 * Providers are independent — own rate limiter, own parser, own models.
 * Adding a new data source means implementing this interface; no changes
 * to the engine or other providers.
 */
interface EnrichmentProvider {

    /** Unique identifier: "musicbrainz", "lrclib", etc. */
    val id: String

    /** Human-readable name for settings UI. */
    val displayName: String

    /** What types of data this provider can supply, with priorities. */
    val capabilities: List<ProviderCapability>

    /** Whether this provider needs a user-supplied API key. */
    val requiresApiKey: Boolean

    /** Whether this provider is currently usable (has key if needed, etc.). */
    val isAvailable: Boolean

    /**
     * Attempt to enrich a music entity for a specific type.
     *
     * @param request The entity to enrich (album, artist, or track)
     * @param type The specific enrichment type to fetch
     * @return Success, NotFound, RateLimited, or Error
     */
    suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult

    /**
     * Search for candidates matching the request. Used for manual search UI
     * where users pick the correct match from a list.
     *
     * @param request The entity to search for
     * @param limit Maximum number of candidates to return
     * @return List of candidates, empty if search not supported
     */
    suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int = 10,
    ): List<SearchCandidate> = emptyList()
}

/**
 * Declares that a provider can supply a specific enrichment type.
 *
 * @param type The enrichment type this capability covers
 * @param priority Higher = tried first. 100 = primary source, 50 = fallback.
 * @param requiresIdentifier True if this capability needs a resolved ID (MBID, etc.)
 *   rather than just title/artist search
 */
data class ProviderCapability(
    val type: EnrichmentType,
    val priority: Int,
    val requiresIdentifier: Boolean = false,
)
