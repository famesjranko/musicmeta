package com.landofoz.musicmeta.provider.discogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for [parseDiscogsRelease]'s boundary search, isolated from the HTTP fixtures in
 * [DiscogsProviderTest] so a boundary-search regression fails here without needing an HTTP fake
 * and a full `enrich()` round trip to reproduce it.
 */
class DiscogsAlbumSelectionTest {

    @Test
    fun `finds the real boundary when a dash-bearing artist has a shorter artist-plausible prefix`() {
        // Given - the requested artist itself contains " - ", so the first boundary's short
        // artist-side ("Artist") still passes ArtistMatcher's loose containment floor
        val combined = "Artist - Name - Album"

        // When - parsing against the true artist and title
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Artist - Name", requestedTitle = "Album")

        // Then - the complete match wins over the first partial one
        assertEquals("Artist - Name" to "Album", parsed)
    }

    @Test
    fun `falls back to the first artist-only boundary when no boundary completes both sides`() {
        // Given - only the first boundary's artist side matches, and its title side matches
        // nothing the request asks for, so no boundary is a complete match
        val combined = "Radiohead - Kid A - Special Edition"

        // When - parsing against a title that does not match either candidate split
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Radiohead", requestedTitle = "OK Computer")

        // Then - the first artist-only boundary stands in, leaving the title rejection to the caller
        assertEquals("Radiohead" to "Kid A - Special Edition", parsed)
    }

    @Test
    fun `returns null when no boundary's artist side matches`() {
        // Given - a combined title naming a wholly different artist
        val combined = "Alabama Shakes - Sound & Color"

        // When - parsing against an unrelated requested artist
        val parsed = parseDiscogsRelease(combined, requestedArtist = "David Bowie", requestedTitle = "Song")

        // Then - no boundary is even a partial match
        assertNull(parsed)
    }

    @Test
    fun `keeps a title that itself contains a dash intact once the artist boundary is found`() {
        // Given - the album title contains " - " after the real artist boundary
        val combined = "Radiohead - Kid A - Special Edition"

        // When - parsing against the exact requested artist and title
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Radiohead", requestedTitle = "Kid A - Special Edition")

        // Then - the whole remainder survives as the title, not just the text before the next dash
        assertEquals("Radiohead" to "Kid A - Special Edition", parsed)
    }
}
