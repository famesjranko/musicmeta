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

    @Test fun `two different all-CJK names do not match`() {
        // Given - a Chinese artist name and an unrelated Japanese artist name
        // When - isMatch is called, where normalization would reduce both to the empty string
        // Then - false is returned, rather than every non-Latin name matching every other
        assertFalse(ArtistMatcher.isMatch("电台司令", "コールドプレイ"))
    }

    @Test fun `identical CJK name still matches`() {
        // Given - the same Japanese name from two different sources
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("コールドプレイ", "コールドプレイ"))
    }

    @Test fun `two different Cyrillic names do not match`() {
        // Given - two unrelated Russian artist names
        // When - isMatch is called
        // Then - false is returned
        assertFalse(ArtistMatcher.isMatch("Мумий Тролль", "Ленинград"))
    }

    @Test fun `identical Cyrillic name still matches`() {
        // Given - the same Russian name from two different sources
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("Ленинград", "Ленинград"))
    }

    @Test fun `two different Hangul names do not match`() {
        // Given - two unrelated Korean artist names
        // When - isMatch is called
        // Then - false is returned
        assertFalse(ArtistMatcher.isMatch("방탄소년단", "블랙핑크"))
    }

    @Test fun `identical Hangul name still matches`() {
        // Given - the same Korean name from two different sources
        // When - isMatch is called
        // Then - true is returned
        assertTrue(ArtistMatcher.isMatch("방탄소년단", "방탄소년단"))
    }

    @Test fun `Latin name does not partially match an unrelated non-Latin name`() {
        // Given - a Latin artist name and an unrelated all-CJK name, where the CJK name
        // normalizes to the empty string that every string trivially "contains"
        // When - isMatch is called
        // Then - false is returned, not a spurious contains match
        assertFalse(ArtistMatcher.isMatch("Radiohead", "电台司令"))
    }
}
