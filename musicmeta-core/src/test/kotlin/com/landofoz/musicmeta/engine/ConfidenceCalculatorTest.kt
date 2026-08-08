package com.landofoz.musicmeta.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidenceCalculatorTest {

    @Test
    fun `idBasedLookup returns 1_0`() {
        // Given — no inputs; idBasedLookup is a constant
        // When — calling idBasedLookup
        // Then — it returns 1.0
        assertEquals(1.0f, ConfidenceCalculator.idBasedLookup(), 0.001f)
    }

    @Test
    fun `authoritative returns 0_95`() {
        // Given — no inputs; authoritative is a constant
        // When — calling authoritative
        // Then — it returns 0.95
        assertEquals(0.95f, ConfidenceCalculator.authoritative(), 0.001f)
    }

    @Test
    fun `searchScore maps score to 0-1 range`() {
        // Given — a score of 85 out of a max of 100
        // When — computing the search score
        // Then — the result is 0.85
        assertEquals(0.85f, ConfidenceCalculator.searchScore(85, 100), 0.001f)
    }

    @Test
    fun `searchScore clamps to 1`() {
        // Given — a score of 150 exceeding the max of 100
        // When — computing the search score
        // Then — the result is clamped to 1.0
        assertEquals(1.0f, ConfidenceCalculator.searchScore(150, 100), 0.001f)
    }

    @Test
    fun `searchScore clamps to 0`() {
        // Given — a score of 0 out of a max of 100
        // When — computing the search score
        // Then — the result is clamped to 0.0
        assertEquals(0.0f, ConfidenceCalculator.searchScore(0, 100), 0.001f)
    }

    @Test
    fun `fuzzyMatch with artist match returns 0_8`() {
        // Given — a fuzzy match where the artist also matches
        // When — computing the fuzzy match confidence
        // Then — the result is 0.8
        assertEquals(0.8f, ConfidenceCalculator.fuzzyMatch(true), 0.001f)
    }

    @Test
    fun `fuzzyMatch without artist match returns 0_6`() {
        // Given — a fuzzy match where the artist does not match
        // When — computing the fuzzy match confidence
        // Then — the result is 0.6
        assertEquals(0.6f, ConfidenceCalculator.fuzzyMatch(false), 0.001f)
    }
}
