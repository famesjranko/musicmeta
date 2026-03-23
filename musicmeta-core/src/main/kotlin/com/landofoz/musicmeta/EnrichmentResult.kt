package com.landofoz.musicmeta

/** Categorizes the type of error for programmatic handling. */
enum class ErrorKind {
    /** Network connectivity or timeout failure. */
    NETWORK,
    /** Authentication or authorization failure (401/403). */
    AUTH,
    /** Response parsing failure (malformed JSON, unexpected schema). */
    PARSE,
    /** Rate limit exceeded (429). */
    RATE_LIMIT,
    /** Engine-level enrichment timeout — type was not resolved before deadline. */
    TIMEOUT,
    /** Uncategorized error. */
    UNKNOWN,
}

/**
 * Describes how identity resolution (MusicBrainz lookup) went for this result.
 *
 * | Value | Meaning | Developer Action |
 * |-------|---------|-----------------|
 * | [RESOLVED] | Identity matched confidently | Show results normally |
 * | [BEST_EFFORT] | Identity failed, results from unverified fuzzy provider searches | Show with caution |
 * | [SUGGESTIONS] | Identity failed, near-miss candidates available | Show "did you mean?" prompt |
 *
 * `null` on [EnrichmentResult.Success.identityMatch] means identity resolution
 * was not needed (MBID was pre-provided) or the result was cached — treat as confident.
 */
enum class IdentityMatch {
    /** Identity resolution matched an entity. [EnrichmentResult.Success.identityMatchScore] has the score. */
    RESOLVED,
    /** Identity resolution failed. Results are from unverified fuzzy provider searches. */
    BEST_EFFORT,
    /** Identity resolution failed but found near-miss candidates. Check [EnrichmentResult.NotFound.suggestions]. */
    SUGGESTIONS,
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
         * Identity resolution match score (0-100), same scale as [SearchCandidate.score].
         * Only set when [identityMatch] is [IdentityMatch.RESOLVED].
         */
        val identityMatchScore: Int? = null,
        /** How identity resolution went for this result. `null` when MBID was pre-provided or cached. */
        val identityMatch: IdentityMatch? = null,
    ) : EnrichmentResult()

    /**
     * Provider searched but found nothing.
     *
     * When [identityMatch] is [IdentityMatch.SUGGESTIONS], [suggestions] carries
     * near-miss candidates. Developers can show a "did you mean?" prompt and
     * re-enrich with the chosen MBID.
     */
    data class NotFound(
        val type: EnrichmentType,
        val provider: String,
        val suggestions: List<SearchCandidate>? = null,
        /** How identity resolution went for this result. `null` when not applicable. */
        val identityMatch: IdentityMatch? = null,
    ) : EnrichmentResult()

    /** Provider is rate limited — try later. */
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
