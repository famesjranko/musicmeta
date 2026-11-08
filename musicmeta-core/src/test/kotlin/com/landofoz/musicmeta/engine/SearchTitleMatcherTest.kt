package com.landofoz.musicmeta.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SearchTitleMatcherTest(
    private val left: String,
    private val right: String,
    private val expected: Boolean,
) {
    @Test
    fun `punctuation equivalence follows the search-only contract`() {
        // Given - two titles and the expected punctuation-equivalence result.

        // When - comparing the parsed titles after deleting Unicode punctuation.
        val result = SearchTitleMatcher.punctuationEquivalent(left, right)

        // Then - the comparison returns the specified result.
        assertEquals(expected, result)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} / {1} = {2}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("Help", "Help!", true),
            arrayOf("S.O.S.", "SOS", true),
            arrayOf("A!!!", "A", true),
            arrayOf("?", "?", false),
            arrayOf("...", "!!!", false),
            arrayOf("1984", "1985", false),
            arrayOf("Help (Live)", "Help", false),
            arrayOf("東京", "東京!", true),
            arrayOf("東京", "大阪", false),
        )
    }
}
