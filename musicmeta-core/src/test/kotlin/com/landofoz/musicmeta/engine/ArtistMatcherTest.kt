package com.landofoz.musicmeta.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistMatcherTest {

    @Test fun `exact match`() {
        // Given - identical names
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("Radiohead", "Radiohead"))
    }

    @Test fun `case insensitive`() {
        // Given - same name, different case
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("radiohead", "Radiohead"))
    }

    @Test fun `the prefix ignored`() {
        // Given - one has "The" prefix, other doesn't
        // When - isMatch is called
        // Then - true is returned either direction
        assertTrue(ArtistMatcher.isMatch("The Beatles", "Beatles"))
        assertTrue(ArtistMatcher.isMatch("Beatles", "The Beatles"))
    }

    @Test fun `punctuation stripped`() {
        // Given - AC/DC with and without slash
        // When - isMatch is called
        // Then - true is returned either way
        assertTrue(ArtistMatcher.isMatch("AC/DC", "ACDC"))
        assertTrue(ArtistMatcher.isMatch("AC/DC", "AC DC"))
    }

    @Test fun `diacritics normalized`() {
        // Given - Björk with and without diacritics
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("Björk", "Bjork"))
    }

    @Test fun `ampersand equals and`() {
        // Given - & vs "and"
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("Simon & Garfunkel", "Simon and Garfunkel"))
    }

    @Test fun `featuring suffix matches base artist`() {
        // Given - candidate has "feat." suffix
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("Massive Attack", "Massive Attack feat. Tricky"))
    }

    @Test fun `different artists do not match`() {
        // Given - completely different artists
        // When - isMatch is called
        // Then - false is returned
        assertFalse(ArtistMatcher.isMatch("Hole", "Alice in Chains"))
        assertFalse(ArtistMatcher.isMatch("Radiohead", "Pink Floyd"))
    }

    @Test fun `blank strings do not match`() {
        // Given - empty or blank inputs
        // When - isMatch is called
        // Then - false is returned
        assertFalse(ArtistMatcher.isMatch("", "Radiohead"))
        assertFalse(ArtistMatcher.isMatch("Radiohead", ""))
        assertFalse(ArtistMatcher.isMatch("", ""))
    }

    @Test fun `partial token overlap matches`() {
        // Given - "Guns N' Roses" vs "Guns N Roses" (apostrophe stripped)
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("Guns N' Roses", "Guns N Roses"))
    }

    @Test fun `low token overlap does not match`() {
        // Given - only one shared word out of many
        // When - isMatch is called
        // Then - false is returned
        assertFalse(ArtistMatcher.isMatch("Red Hot Chili Peppers", "Red House Painters"))
    }
}
