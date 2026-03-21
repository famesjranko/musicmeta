package com.landofoz.musicmeta.engine

import java.text.Normalizer

/**
 * Music-aware string matching for verifying search results.
 * Handles common variations in artist/album naming across APIs.
 */
object ArtistMatcher {

    /** Default minimum fraction of expected tokens that must appear in candidate. */
    const val DEFAULT_MIN_TOKEN_OVERLAP = 0.5f

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
    ): Boolean {
        if (expected.isBlank() || candidate.isBlank()) return false

        val normExpected = normalize(expected)
        val normCandidate = normalize(candidate)

        // Exact match after normalization
        if (normExpected == normCandidate) return true

        // Compact form (no spaces) — catches AC/DC vs ACDC
        val compactExpected = normExpected.replace(" ", "")
        val compactCandidate = normCandidate.replace(" ", "")
        if (compactExpected == compactCandidate) return true

        // One contains the other (handles "feat." suffixes)
        if (normCandidate.contains(normExpected) || normExpected.contains(normCandidate)) return true

        // Token overlap — at least minTokenOverlap of expected tokens appear in candidate
        val expectedTokens = tokenize(normExpected)
        val candidateTokens = tokenize(normCandidate)
        if (expectedTokens.isEmpty()) return false

        val overlap = expectedTokens.count { it in candidateTokens }
        return overlap.toFloat() / expectedTokens.size >= minTokenOverlap
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
    private fun normalize(name: String): String {
        var s = name.lowercase().trim()
        // Strip diacritics
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        // Normalize & → and
        s = s.replace("&", " and ")
        // Replace punctuation with space (so AC/DC → ac dc, not acdc)
        s = s.replace(Regex("[^a-z0-9 ]"), " ")
        // Remove leading "the "
        s = s.removePrefix("the ")
        // Collapse whitespace
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun tokenize(normalized: String): Set<String> =
        normalized.split(" ").filter { it.isNotBlank() }.toSet()
}
