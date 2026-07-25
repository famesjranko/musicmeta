package com.landofoz.musicmeta.engine

/**
 * Standardized confidence scoring for provider results.
 * Convention utility -- providers use these methods for consistency
 * instead of hardcoding raw float values.
 *
 * | Score | Method | When to Use |
 * |-------|--------|-------------|
 * | 1.0   | idBasedLookup() | Deterministic MBID/ID lookup |
 * | 0.95  | authoritative() | Authoritative source with high-quality match |
 * | 0-1   | searchScore() | Provider returns its own match score |
 * | 0.8   | fuzzyMatch(true) | Artist name matched, good catalog |
 * | 0.6   | fuzzyMatch(false) | No artist verification, weaker match |
 */
internal object ConfidenceCalculator {

    /** Deterministic lookup by exact ID (MBID, Wikidata QID, etc.) */
    fun idBasedLookup(): Float = 1.0f

    /** Authoritative source with high-quality data (Wikipedia, exact LRCLIB match, MBID-based Fanart.tv/Wikidata) */
    fun authoritative(): Float = 0.95f

    /** Map a provider's own match score to 0.0-1.0 range. */
    fun searchScore(score: Int, max: Int = 100): Float =
        (score.toFloat() / max).coerceIn(0f, 1f)

    /** Fuzzy text search match. Higher confidence if artist name was verified. */
    fun fuzzyMatch(hasArtistMatch: Boolean): Float =
        if (hasArtistMatch) 0.8f else 0.6f
}
