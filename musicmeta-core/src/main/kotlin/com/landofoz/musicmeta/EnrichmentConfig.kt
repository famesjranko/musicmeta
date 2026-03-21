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
 */
data class EnrichmentConfig(
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    val userAgent: String = DEFAULT_USER_AGENT,
    val enableIdentityResolution: Boolean = true,
    val enrichTimeoutMs: Long = DEFAULT_ENRICH_TIMEOUT_MS,
    val confidenceOverrides: Map<String, Float> = emptyMap(),
    val priorityOverrides: Map<String, Map<EnrichmentType, Int>> = emptyMap(),
) {
    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.5f
        const val DEFAULT_USER_AGENT = "MusicEnrichmentEngine/1.0"
        const val DEFAULT_ENRICH_TIMEOUT_MS = 30_000L
    }
}
