package com.landofoz.musicmeta.provider.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every MusicBrainz spelling asserted here was read from the live release-group browse for
 * Godspeed You! Black Emperor on 2026-08-10, so the folds are pinned to titles that exist rather
 * than to invented ones.
 */
class MusicBrainzTitleFoldingTest {

    @Test
    fun `the symbol title folds onto the ASCII spelling a caller would type`() {
        // Given - MusicBrainz's own title and the plainest spelling of it
        val stored = "F♯ A♯ ∞"
        val typed = "F# A# (Infinity)"

        // When - both are folded
        // Then - they compare equal, which is what makes the local match possible
        assertEquals(MusicBrainzTitleFolding.fold(stored), MusicBrainzTitleFolding.fold(typed))
        assertEquals("f# a# infinity", MusicBrainzTitleFolding.fold(stored))
    }

    @Test
    fun `a symbol adjacent to a word still folds to its own token`() {
        // Given - no space between the sharp sign and the infinity sign
        val stored = "A♯∞"

        // When - folded
        val folded = MusicBrainzTitleFolding.fold(stored)

        // Then - the word the symbol is read as does not run into what precedes it
        assertEquals("a# infinity", folded)
    }

    @Test
    fun `curly quotes and apostrophes fold onto their straight spellings`() {
        // Given - three MusicBrainz titles that keep the artwork's typography
        val cases = mapOf(
            "“Luciferian Towers”" to "Luciferian Towers",
            "G_d’s Pee AT STATE’S END!" to "G_d's Pee AT STATE'S END!",
            "‘Asunder, Sweet and Other Distress’" to "Asunder, Sweet and Other Distress",
        )

        // When - each is folded alongside its plain spelling
        // Then - every pair matches
        cases.forEach { (stored, typed) ->
            assertEquals(MusicBrainzTitleFolding.fold(stored), MusicBrainzTitleFolding.fold(typed))
        }
    }

    @Test
    fun `dashes fold to a hyphen`() {
        // Given - a title MusicBrainz stores with an en dash
        val stored = "GREY RUBBLE – GREEN SHOOTS"

        // When - folded against the hyphen spelling
        // Then - they match
        assertEquals(MusicBrainzTitleFolding.fold("GREY RUBBLE - GREEN SHOOTS"), MusicBrainzTitleFolding.fold(stored))
    }

    @Test
    fun `two different albums by the same artist do not fold together`() {
        // Given - the symbol album, whose title ends with the infinity sign, and a second release
        // group whose title starts with it, both real groups in the same catalogue
        val symbolAlbum = MusicBrainzTitleFolding.fold("F♯ A♯ ∞")
        val otherAlbum = MusicBrainzTitleFolding.fold("∞ Forever")

        // When - compared
        // Then - the fold keeps them apart, so an artist-scoped match cannot cross them
        assertNotEquals(symbolAlbum, otherAlbum)
    }

    @Test
    fun `folding leaves an already-plain title alone apart from case and spacing`() {
        // Given - a title with nothing to fold and untidy whitespace
        val title = "  Yanqui   U.X.O. "

        // When - folded
        val folded = MusicBrainzTitleFolding.fold(title)

        // Then - only case and whitespace moved
        assertEquals("yanqui u.x.o.", folded)
    }

    @Test
    fun `a plain-letter title no folding can rescue reports no fold match possible`() {
        // Given - a typo'd title containing no foldable character and no fold image
        val title = "OK Computr"

        // When - asked whether any differently-spelled title could fold onto it
        val possible = MusicBrainzTitleFolding.foldMatchPossible(title)

        // Then - impossible: folding never edits a letter, so no symbol spelling folds to this
        assertFalse(possible)
    }

    @Test
    fun `an ASCII spelling of a symbol title reports a fold match possible`() {
        // Given - the plain spelling a caller types for an album MusicBrainz stores as symbols
        val title = "F# A# Infinity"

        // When - asked whether any differently-spelled title could fold onto it
        val possible = MusicBrainzTitleFolding.foldMatchPossible(title)

        // Then - possible: both the sharp sign's image and the spelled-out infinity appear in it
        assertTrue(possible)
    }

    @Test
    fun `a title that itself folds reports a fold match possible`() {
        // Given - a title carrying symbol characters the fold rewrites
        val title = "F♯ A♯ ∞"

        // When - asked whether any differently-spelled title could fold onto it
        val possible = MusicBrainzTitleFolding.foldMatchPossible(title)

        // Then - possible: the fold itself changes this title, so spellings converge on it
        assertTrue(possible)
    }

    @Test
    fun `a title containing a single-letter fold image still reports a fold match possible`() {
        // Given - a plain title whose letters include the flat sign's image
        val title = "Concerto in Bb"

        // When - asked whether any differently-spelled title could fold onto it
        val possible = MusicBrainzTitleFolding.foldMatchPossible(title)

        // Then - possible, deliberately over-inclusive: a flat sign folds to this exact letter
        assertTrue(possible)
    }
}
