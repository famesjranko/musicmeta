package com.landofoz.musicmeta.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatcherTest {

    @Test
    fun `parses a plain title with no qualifier`() {
        // Given - a title with no trailing bracket or dash group
        // When - parsing it
        val parts = TitleMatcher.parse("Starman")

        // Then - the base is the whole normalized title and there is no qualifier
        assertEquals("starman", parts.base)
        assertNull(parts.qualifier)
    }

    @Test
    fun `parses a terminal parenthetical group as the qualifier`() {
        // Given - a title with a trailing parenthetical
        // When - parsing it
        val parts = TitleMatcher.parse("Starman (2012 Remaster)")

        // Then - the base excludes the group and the qualifier is its trimmed content
        assertEquals("starman", parts.base)
        assertEquals("2012 remaster", parts.qualifier)
    }

    @Test
    fun `parses a terminal spaced-dash group as the qualifier`() {
        // Given - a title with a trailing " - qualifier" group
        // When - parsing it
        val parts = TitleMatcher.parse("Starman - 2012 Remaster")

        // Then - the base excludes the dash group and the qualifier is its trimmed content
        assertEquals("starman", parts.base)
        assertEquals("2012 remaster", parts.qualifier)
    }

    @Test
    fun `parses a terminal square-bracket group as the qualifier`() {
        // Given - a title with a trailing square-bracket group
        // When - parsing it
        val parts = TitleMatcher.parse("Starman [2012 Remaster]")

        // Then - the base excludes the group and the qualifier is its trimmed content
        assertEquals("starman", parts.base)
        assertEquals("2012 remaster", parts.qualifier)
    }

    @Test
    fun `equivalent titles ignore case and whitespace`() {
        // Given - the same title with different case and spacing
        // When - comparing them
        val result = TitleMatcher.equivalent("  STARMAN  ", "starman")

        // Then - they are equivalent
        assertTrue(result)
    }

    @Test
    fun `equivalent titles ignore surrounding cosmetic quotes`() {
        // Given - Deezer's quoted-title decoration on one side only
        // When - comparing it against the plain title
        val result = TitleMatcher.equivalent("\"Heroes\"", "Heroes")

        // Then - the quotes are cosmetic and do not change the comparison
        assertTrue(result)
    }

    @Test
    fun `equivalent titles normalize en-dash and em-dash qualifier syntax`() {
        // Given - the same qualifier spelled with an en dash and an ASCII hyphen
        // When - comparing them
        val result = TitleMatcher.equivalent("Starman – 2012 Remaster", "Starman - 2012 Remaster")

        // Then - the dash characters are normalized before comparison
        assertTrue(result)
    }

    @Test
    fun `a dash-delimited qualifier equals the same qualifier in brackets`() {
        // Given - the same qualifier expressed with different delimiter syntax
        // When - comparing them
        val result = TitleMatcher.equivalent("Wish You Were Here - Live", "Wish You Were Here (Live)")

        // Then - they are equivalent
        assertTrue(result)
    }

    @Test
    fun `a present qualifier is never equivalent to an absent one`() {
        // Given - one title has a qualifier and the other is bare
        // When - comparing them
        val result = TitleMatcher.equivalent("Song - Live", "Song")

        // Then - they are not equivalent
        assertFalse(result)
    }

    @Test
    fun `different qualifiers on the same base are never equivalent`() {
        // Given - two different qualifiers on the same base title
        // When - comparing them
        val result = TitleMatcher.equivalent("Song - Live", "Song - Acoustic")

        // Then - they are not equivalent
        assertFalse(result)
    }

    @Test
    fun `different base titles sharing a qualifier are never equivalent`() {
        // Given - a wholly different base title that happens to share a remaster qualifier
        // When - comparing them
        val result = TitleMatcher.equivalent("Song (2015 Remaster)", "Song for Bob Dylan (2015 Remaster)")

        // Then - they are not equivalent
        assertFalse(result)
    }
}
