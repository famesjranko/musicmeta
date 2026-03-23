package com.landofoz.musicmeta

/**
 * Configuration for the enrichment engine.
 *
 * @param minConfidence Minimum confidence score (0.0–1.0) to accept a result.
 *   Results below this threshold are treated as NotFound. See [EnrichmentResult]
 *   for the confidence scoring guidelines.
 * @param userAgent User-Agent header sent with all HTTP requests.
 *   MusicBrainz and Wikimedia require a descriptive User-Agent.
 * @param enableIdentityResolution Whether to auto-resolve MBIDs via MusicBrainz
 *   before fanning out to other providers.
 * @param confidenceOverrides Per-provider confidence overrides. Key = provider ID
 *   (e.g., "deezer", "itunes"). When set, the provider's hardcoded confidence
 *   is replaced with this value. Useful for tuning without editing provider code.
 * @param priorityOverrides Per-provider priority overrides. Outer key = provider ID,
 *   inner key = enrichment type, value = priority (higher = tried first).
 *   Overrides the provider's built-in priority for chain ordering.
 *   Example: `mapOf("deezer" to mapOf(EnrichmentType.ALBUM_ART to 90))`
 * @param ttlOverrides Per-type TTL overrides in milliseconds. Overrides
 *   [EnrichmentType.defaultTtlMs] when present.
 * @param catalogProvider Optional catalog provider for availability-based filtering of
 *   recommendation results. When null, filtering is skipped (UNFILTERED behavior).
 * @param catalogFilterMode How to apply catalog filtering when a catalogProvider is present.
 *   Defaults to UNFILTERED (no filtering).
 */
data class EnrichmentConfig(
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    val userAgent: String = DEFAULT_USER_AGENT,
    val enableIdentityResolution: Boolean = true,
    val enrichTimeoutMs: Long = DEFAULT_ENRICH_TIMEOUT_MS,
    val confidenceOverrides: Map<String, Float> = emptyMap(),
    val priorityOverrides: Map<String, Map<EnrichmentType, Int>> = emptyMap(),
    val ttlOverrides: Map<EnrichmentType, Long> = emptyMap(),
    val catalogProvider: CatalogProvider? = null,
    val catalogFilterMode: CatalogFilterMode = CatalogFilterMode.UNFILTERED,
    /** Max tracks returned for ARTIST_RADIO. Deezer supports up to 100. */
    val radioLimit: Int = DEFAULT_RADIO_LIMIT,
    /** Max tracks returned for ARTIST_TOP_TRACKS per provider (before merge). */
    val topTracksLimit: Int = DEFAULT_TOP_TRACKS_LIMIT,
) {
    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.5f
        const val DEFAULT_USER_AGENT = "MusicEnrichmentEngine/1.0"
        const val DEFAULT_ENRICH_TIMEOUT_MS = 30_000L
        const val DEFAULT_RADIO_LIMIT = 50
        const val DEFAULT_TOP_TRACKS_LIMIT = 50
    }
}

/**
 * Centralized API key configuration for all providers that need keys.
 * Pass to [EnrichmentEngine.Builder.apiKeys] to enable key-requiring providers
 * when using [EnrichmentEngine.Builder.withDefaultProviders].
 */
data class ApiKeyConfig(
    val lastFmKey: String? = null,
    val fanartTvProjectKey: String? = null,
    val discogsPersonalToken: String? = null,
)
