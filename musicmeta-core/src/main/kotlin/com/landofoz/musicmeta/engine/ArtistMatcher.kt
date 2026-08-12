package com.landofoz.musicmeta.engine

import java.text.Normalizer

/**
 * Music-aware string matching for verifying search results.
 * Handles common variations in artist/album naming across APIs.
 */
internal object ArtistMatcher {

    /** Default minimum fraction of expected tokens that must appear in candidate. */
    const val DEFAULT_MIN_TOKEN_OVERLAP = 0.5f

    /** [matchQuality]: same name once normalized, or once spaces are dropped (AC/DC vs ACDC). */
    const val QUALITY_SAME_NAME = 3

    /** [matchQuality]: one name contains the other — "Radiohead" vs "DJ Radiohead". */
    const val QUALITY_CONTAINS = 2

    /** [matchQuality]: only enough tokens overlap — "Bad Company" vs "Bad Bunny". */
    const val QUALITY_TOKEN_OVERLAP = 1

    /** [matchQuality]: not a plausible match at all. */
    const val QUALITY_NONE = 0

    /**
     * Returns true if [candidate] is a plausible match for [expected].
     * Handles: case, "The" prefix, punctuation, diacritics, feat. credits,
     * "&" vs "and", and token overlap for partial matches.
     *
     * @param minTokenOverlap Minimum fraction (0.0–1.0) of expected tokens that
     *   must appear in the candidate for a token-overlap match. Lower values
     *   accept fuzzier matches. Default is 0.5 (50%).
     */
    fun isMatch(
        expected: String,
        candidate: String,
        minTokenOverlap: Float = DEFAULT_MIN_TOKEN_OVERLAP,
    ): Boolean = matchQuality(expected, candidate, minTokenOverlap) > QUALITY_NONE

    /**
     * How *well* [candidate] matches [expected], as one of the `QUALITY_` ranks above —
     * the tiers [isMatch] already tests, kept rather than collapsed to a boolean.
     *
     * Use it to rank candidates that [isMatch] all accepts. Ranking matters because [isMatch] is
     * deliberately loose at the bottom: for "Bad Company" it accepts both "Bad Company Live In
     * Concert" (contains) and "Bad Bunny" (half the tokens). Sorting on a popularity signal alone
     * would hand that pool to Bad Bunny, so sort on this first and let popularity break ties
     * *within* a rank.
     *
     * @param minTokenOverlap as [isMatch].
     */
    fun matchQuality(
        expected: String,
        candidate: String,
        minTokenOverlap: Float = DEFAULT_MIN_TOKEN_OVERLAP,
    ): Int {
        if (expected.isBlank() || candidate.isBlank()) return QUALITY_NONE

        // normalize() keeps [a-z0-9 ] only, so a name written entirely in another script
        // (电台司令, コールドプレイ) collapses to "" — and two empty strings would compare equal.
        // When neither side has Latin content to normalize, compare the raw names instead
        // (case-folded, trimmed) — same fallback as NameMatchTier.sameName.
        if (!hasLatinAlphanumeric(expected) && !hasLatinAlphanumeric(candidate)) {
            return if (expected.trim().equals(candidate.trim(), ignoreCase = true)) {
                QUALITY_SAME_NAME
            } else {
                QUALITY_NONE
            }
        }

        val normExpected = normalize(expected)
        val normCandidate = normalize(candidate)

        // Exact match after normalization
        if (normExpected == normCandidate) return QUALITY_SAME_NAME

        // Compact form (no spaces) — catches AC/DC vs ACDC
        val compactExpected = normExpected.replace(" ", "")
        val compactCandidate = normCandidate.replace(" ", "")
        if (compactExpected == compactCandidate) return QUALITY_SAME_NAME

        // One contains the other (handles "feat." suffixes). Guarded against empty: when one side
        // is Latin and the other is entirely non-Latin, normalize() reduces the non-Latin side to
        // "" — and every string trivially "contains" "", which would rank an unrelated non-Latin
        // name as a partial match.
        val bothNonEmpty = normExpected.isNotEmpty() && normCandidate.isNotEmpty()
        if (bothNonEmpty && (normCandidate.contains(normExpected) || normExpected.contains(normCandidate))) {
            return QUALITY_CONTAINS
        }

        // Token overlap — at least minTokenOverlap of expected tokens appear in candidate
        val expectedTokens = tokenize(normExpected)
        val candidateTokens = tokenize(normCandidate)
        if (expectedTokens.isEmpty()) return QUALITY_NONE

        val overlap = expectedTokens.count { it in candidateTokens }
        val enough = overlap.toFloat() / expectedTokens.size >= minTokenOverlap
        return if (enough) QUALITY_TOKEN_OVERLAP else QUALITY_NONE
    }

    /**
     * Normalize a name for comparison:
     * - Lowercase
     * - Strip diacritics (Björk → bjork)
     * - Remove "the" prefix
     * - Normalize "&" to "and"
     * - Strip punctuation (AC/DC → acdc)
     * - Collapse whitespace
     */
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9 ]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val LATIN_ALPHANUMERIC_REGEX = Regex("[a-z0-9]")

    private fun hasLatinAlphanumeric(name: String): Boolean =
        LATIN_ALPHANUMERIC_REGEX.containsMatchIn(name.lowercase())

    private fun normalize(name: String): String {
        var s = name.lowercase().trim()
        // Strip diacritics
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
        // Normalize & → and
        s = s.replace("&", " and ")
        // Replace punctuation with space (so AC/DC → ac dc, not acdc)
        s = s.replace(NON_ALPHANUMERIC_REGEX, " ")
        // Remove leading "the "
        s = s.removePrefix("the ")
        // Collapse whitespace
        s = s.replace(WHITESPACE_REGEX, " ").trim()
        return s
    }

    private fun tokenize(normalized: String): Set<String> =
        normalized.split(" ").filter { it.isNotBlank() }.toSet()
}

/**
 * The best match for [expected] in a search pool, or null if nothing in it matches.
 *
 * The selection rule every name-search provider needs (`docs/pitfalls.md` §7): keep the candidates
 * [ArtistMatcher.isMatch] accepts, rank them by [ArtistMatcher.matchQuality], and let the provider's
 * own order settle a tie — `maxWithOrNull` keeps the first maximum, so hit 0 wins among equals.
 *
 * @param nameOf pulls the name to match from a candidate; the field and any cleanup are per API.
 * @param tieBreak an optional popularity comparator, applied *within* a quality rank and never
 *   across ranks — sorting the whole pool on popularity picks "Bad Bunny" for "Bad Company".
 *   Providers whose search payload carries no popularity signal pass nothing.
 */
internal fun <T> Iterable<T>.bestArtistMatch(
    expected: String,
    tieBreak: Comparator<T>? = null,
    nameOf: (T) -> String,
): T? {
    val byQuality = compareBy<T> { ArtistMatcher.matchQuality(expected, nameOf(it)) }
    return filter { ArtistMatcher.isMatch(expected, nameOf(it)) }
        .maxWithOrNull(if (tieBreak == null) byQuality else byQuality.then(tieBreak))
}
