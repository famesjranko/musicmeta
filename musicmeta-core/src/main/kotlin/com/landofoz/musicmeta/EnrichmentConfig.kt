package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.CacheMode

/**
 * Discovery depth for LB Radio requests.
 * Controls how adventurous/experimental the generated playlist is.
 */
enum class RadioDiscoveryMode(val apiValue: String) {
    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard"),
}

/**
 * Configuration for the enrichment engine.
 *
 * @param minConfidence Minimum confidence score (0.0–1.0) to accept a result.
 *   Results below this threshold are treated as NotFound. See [EnrichmentResult]
 *   for the confidence scoring guidelines.
 * @param userAgent User-Agent header sent with all HTTP requests. MusicBrainz and Wikimedia both
 *   require it to carry contact information — see [DEFAULT_USER_AGENT] for what the default costs
 *   you, and [EnrichmentEngine.Builder.contact] for the shortest way to comply.
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
 * @param cacheMode Controls fallback behavior when providers fail. NETWORK_FIRST (default)
 *   returns errors as-is. STALE_IF_ERROR serves expired cache entries on Error/RateLimited.
 * @param negativeTtlMs How long a "providers had nothing" answer is cached before it is re-asked,
 *   in milliseconds. Short relative to [ttlOverrides]/[EnrichmentType.defaultTtlMs] so a
 *   newly-published entity is picked up soon after a miss, while a repeat lookup inside the
 *   window skips the round-trip. Default is one hour.
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
    /** Discovery depth for LB Radio (ARTIST_RADIO_DISCOVERY). */
    val radioDiscoveryMode: RadioDiscoveryMode = RadioDiscoveryMode.EASY,
    val cacheMode: CacheMode = CacheMode.NETWORK_FIRST,
    val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
) {
    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.5f

        /**
         * The User-Agent sent when a consumer supplies none. **It carries no contact information,
         * which two of the shipped providers' policies require.** MusicBrainz asks for "enough
         * information in the User-Agent string for us to contact the maintainers" and throttles
         * user agents without it against one shared anonymous pool; Wikimedia's policy requires
         * `<client name>/<version> (<contact information>)` and says generic user agents "may be
         * blocked" with a 403.
         *
         * Supply contact information via [EnrichmentEngine.Builder.contact], or set [userAgent]
         * outright. Building an engine on this default with MusicBrainz, Wikipedia or Wikidata
         * registered logs one warning.
         */
        const val DEFAULT_USER_AGENT = "MusicEnrichmentEngine/1.0"
        const val DEFAULT_ENRICH_TIMEOUT_MS = 30_000L
        const val DEFAULT_RADIO_LIMIT = 50
        const val DEFAULT_NEGATIVE_TTL_MS = 60 * 60 * 1000L

        /**
         * [DEFAULT_USER_AGENT] carrying [contact], in the form both policies ask for:
         * `MusicEnrichmentEngine/1.0 ( contact )`.
         *
         * [contact] is a URL or email address a provider's operators can reach you at; it is
         * trimmed and otherwise used as given. Callers who set [userAgent] themselves do not need
         * this — put the contact inside their own string instead.
         *
         * @throws IllegalArgumentException if [contact] is blank, carries a line break, or carries
         *   a parenthesis — see [EnrichmentEngine.Builder.contact].
         */
        fun userAgentWithContact(contact: String): String {
            requireUsableContact(contact)
            return "$DEFAULT_USER_AGENT ( ${contact.trim()} )"
        }
    }
}

/**
 * Rejects a contact string that would break the User-Agent it goes into. Both callers validate at
 * the point the string is supplied: a CR or LF reaches the wire as a per-request
 * `IllegalArgumentException` thrown inside the connection open, which every provider reports as an
 * ordinary failure, and a parenthesis closes the comment both policies grade the header on.
 */
internal fun requireUsableContact(contact: String) {
    require(contact.isNotBlank()) { "contact must be a URL or email address, not blank" }
    val lineBreak = contact.firstOrNull { it == '\r' || it == '\n' }
    require(lineBreak == null) {
        "contact must not contain ${if (lineBreak == '\r') "a carriage return" else "a line feed"}: " +
            "a header value carrying one is rejected as the connection opens, failing every request"
    }
    val paren = contact.firstOrNull { it == '(' || it == ')' }
    require(paren == null) {
        "contact must not contain '$paren': it closes the User-Agent comment " +
            "\"${EnrichmentConfig.DEFAULT_USER_AGENT} ( contact )\" that the MusicBrainz and " +
            "Wikimedia policies read"
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
    /** ListenBrainz user token for LB Radio (ARTIST_RADIO_DISCOVERY). Keyless endpoints still work without this. */
    val listenBrainzToken: String? = null,
)
