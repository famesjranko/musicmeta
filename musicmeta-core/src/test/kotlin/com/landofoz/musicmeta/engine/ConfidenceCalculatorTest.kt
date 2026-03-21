package com.landofoz.musicmeta.engine

import org.junit.Test
import kotlin.test.assertEquals

class ConfidenceCalculatorTest {

    @Test
    fun `idBasedLookup returns 1_0`() {
        assertEquals(1.0f, ConfidenceCalculator.idBasedLookup())
    }

    @Test
    fun `authoritative returns 0_95`() {
        assertEquals(0.95f, ConfidenceCalculator.authoritative())
    }

    @Test
    fun `searchScore maps score to 0-1 range`() {
        assertEquals(0.85f, ConfidenceCalculator.searchScore(85, 100))
    }

    @Test
    fun `searchScore clamps to 1`() {
        assertEquals(1.0f, ConfidenceCalculator.searchScore(150, 100))
    }

    @Test
    fun `searchScore clamps to 0`() {
        assertEquals(0.0f, ConfidenceCalculator.searchScore(0, 100))
    }

    @Test
    fun `fuzzyMatch with artist match returns 0_8`() {
        assertEquals(0.8f, ConfidenceCalculator.fuzzyMatch(true))
    }

    @Test
    fun `fuzzyMatch without artist match returns 0_6`() {
        assertEquals(0.6f, ConfidenceCalculator.fuzzyMatch(false))
    }
}
