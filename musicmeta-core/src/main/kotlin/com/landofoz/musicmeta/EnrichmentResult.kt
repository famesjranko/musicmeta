package com.landofoz.musicmeta

/** Categorizes the type of error for programmatic handling. */
enum class ErrorKind {
    /** Network connectivity or timeout failure. */
    NETWORK,

    /** Authentication or authorization failure (401/403). */
    AUTH,

    /** Response parsing failure (malformed JSON, unexpected schema). */
    PARSE,

    /**
     * The upstream throttled the request (HTTP 429) and the retry ladder did not outlast it.
     *
     * Set by [EnrichmentProvider.mapError], and normally widened to [EnrichmentResult.RateLimited]
     * before a consumer sees it — so a branch on this value is reached only where the widening does
     * not apply. Distinct from [NETWORK], which is every other transient transport failure (5xx, a
     * dropped connection): a 429 is worth retrying later, not immediately. See
     * `docs/guides/results-and-errors.md`.
     */
    RATE_LIMIT,

    /** Engine-level enrichment timeout — type was not resolved before deadline. */
    TIMEOUT,

    /** Uncategorized error. */
    UNKNOWN,

    /**
     * The engine was `close()`d before this type settled — not a timeout, not a provider failure,
     * and not an internal fault, which settles [UNKNOWN] carrying its own cause instead.
     */
    ENGINE_CLOSED,
}

/**
 * Outcome of an enrichment attempt for a single type.
 *
 * ## Confidence Scoring
 *
 * The `confidence` field in [Success] indicates how reliable the match is,
 * on a 0.0–1.0 scale. The engine's [EnrichmentConfig.minConfidence] (default 0.5)
 * filters out low-confidence results, treating them as [NotFound].
 *
 * It scores **how the result was obtained, not whether it is the entity the caller described.** A
 * lookup by a caller-supplied identifier is deterministic and scores 1.0 whether or not that
 * identifier names what the request named — a wrong-but-live MBID resolves perfectly. Whether the
 * request's own evidence agreed is [IdentityResolution.status]'s question, and
 * [CanonicalStatus.CONTRADICTED] is the only field that reports it disagreeing.
 *
 * **Scoring guidelines for provider implementors:**
 *
 * | Score Range | Match Type | Examples |
 * |-------------|------------|----------|
 * | **1.0**     | Deterministic — result looked up by exact ID | CAA by MBID, MusicBrainz direct MBID lookup |
 * | **0.90–0.99** | Authoritative source, high-quality match | Wikipedia bio, Wikidata photo, LRCLIB exact match (artist+track+album+duration) |
 * | **0.70–0.89** | Good fuzzy match from a large catalog | Deezer search, Last.fm tags, ListenBrainz popularity, LRCLIB search-only match |
 * | **0.50–0.69** | Weak fuzzy match, may be wrong | iTunes search, Discogs search (physical-release focus) |
 * | **< 0.50**  | Unreliable — filtered out by default | Should rarely be returned |
 *
 * When the upstream API provides its own match score (e.g., MusicBrainz returns
 * 0–100), map it to 0.0–1.0 directly rather than using a hardcoded value.
 */
sealed class EnrichmentResult {

    /** Provider found data successfully. */
    data class Success(
        val type: EnrichmentType,
        val data: EnrichmentData,
        val provider: String,
        val confidence: Float,
        /** Identifiers resolved during enrichment (e.g., MBIDs from identity resolution). */
        val resolvedIdentifiers: EnrichmentIdentifiers? = null,
        /**
         * How this provider selected the entity behind [data]. `null` only for a result a
         * consumer built outside the engine (e.g. a test fixture) — every engine-produced
         * `Success` sets it. See [EnrichmentResults.identity] for whether MusicBrainz agreed.
         */
        val provenance: LookupProvenance? = null,
        /**
         * True when this result was served from an expired cache entry because the provider
         * returned an error. Consumers can show a staleness indicator or schedule a retry.
         */
        val isStale: Boolean = false,
        /**
         * True when this is a recommendation type whose [CatalogProvider] threw during availability
         * checking, so the data reached here as the fetched providers returned it rather than as
         * [EnrichmentConfig.catalogFilterMode] ranked or trimmed it. Never true when the mode is
         * [CatalogFilterMode.UNFILTERED] — that is a deliberate configuration, not a degradation.
         * Consumers can show an "unranked" indicator or omit availability-dependent UI for this result.
         *
         * Call-scoped, not a stored fact: every serve — live or a cache hit — is normalized to
         * `false` before this call's own [CatalogProvider] check runs (or is skipped, e.g. under
         * [CatalogFilterMode.UNFILTERED]), and only *this* call's own throw can set it back to
         * `true`. A value carried on a `Success` handed in from a cache read never survives that
         * normalization. No shipped [EnrichmentCache] implementation persists it — a stored
         * [CatalogProvider] failure that has since recovered would otherwise haunt every later
         * cache hit, and a healthy write would mask a [CatalogProvider] that started failing after
         * it was cached.
         */
        val isCatalogDegraded: Boolean = false,
    ) : EnrichmentResult()

    /**
     * Provider searched but found nothing.
     *
     * [suggestions] describes this specific provider's own search, not the canonical identity
     * attempt — check [EnrichmentResults.identity] for near-miss candidates to show as a
     * "did you mean?" prompt.
     */
    data class NotFound(
        val type: EnrichmentType,
        val provider: String,
        val suggestions: List<SearchCandidate>? = null,
    ) : EnrichmentResult()

    /**
     * An upstream throttled the request (HTTP 429) and the retry ladder did not outlast it.
     *
     * The engine widens a provider's `Error(ErrorKind.RATE_LIMIT)` into this before returning it, so
     * a 429 is distinguishable from every other transient transport failure without every provider
     * having to construct it. [retryAfterMs] carries the upstream's `Retry-After` when it sent one.
     * A throttled provider counts against its circuit breaker, so a sustained 429 opens it. See
     * `docs/guides/results-and-errors.md`.
     */
    data class RateLimited(
        val type: EnrichmentType,
        val provider: String,
        val retryAfterMs: Long? = null,
    ) : EnrichmentResult()

    /** Provider encountered an error. */
    data class Error(
        val type: EnrichmentType,
        val provider: String,
        val message: String,
        val cause: Throwable? = null,
        val errorKind: ErrorKind = ErrorKind.UNKNOWN,
    ) : EnrichmentResult()
}
