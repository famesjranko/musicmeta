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

    @Test
    fun `a request naming one of two comma-joined credited artists finds the boundary`() {
        // Given - Discogs credits two artists on one release, joined by a comma, in Greek
        val combined = "Χάρις Αλεξίου, Αντώνης Βαρδής - Ξημερώνει"

        // When - parsing against one of the two, whose script leaves the whole credit unmatchable
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Χάρις Αλεξίου", requestedTitle = "Ξημερώνει")

        // Then - the credit is the artist side, whole, and the title side is the album
        assertEquals("Χάρις Αλεξίου, Αντώνης Βαρδής" to "Ξημερώνει", parsed)
    }

    @Test
    fun `a credit marked as a name variation matches the name under the marker`() {
        // Given - Discogs' name-variation marker on a credit whose script leaves it unnormalizable
        val combined = "東京事変* - 教育"

        // When - parsing against the very name the marker is attached to
        val parsed = parseDiscogsRelease(combined, requestedArtist = "東京事変", requestedTitle = "教育")

        // Then - the marker is punctuation of Discogs' own, not part of the name it decorates
        assertEquals("東京事変*" to "教育", parsed)
    }

    @Test
    fun `only the marked part of a comma-joined credit loses its marker`() {
        // Given - a two-artist credit whose second name is a variation
        val combined = "Χάρις Αλεξίου, Αντώνη Βαρδή* - Ξημερώνει"

        // When - parsing against that second, marked name
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Αντώνη Βαρδή", requestedTitle = "Ξημερώνει")

        // Then - the credit is the artist side whole, marker included, as Discogs printed it
        assertEquals("Χάρις Αλεξίου, Αντώνη Βαρδή*" to "Ξημερώνει", parsed)
    }

    @Test
    fun `an asterisk inside a credit is part of the name and survives`() {
        // Given - a live credit carrying markers between its names as well as at the end
        val combined = "Αλεξίου* • Μάλαμας* • Ιωαννίδης* - Ζωντανή Ηχογράφηση"

        // When - parsing against the same string with every asterisk gone, interior ones included
        val parsed = parseDiscogsRelease(
            combined,
            requestedArtist = "Αλεξίου • Μάλαμας • Ιωαννίδης",
            requestedTitle = "Ζωντανή Ηχογράφηση",
        )

        // Then - the convention is positional, so an interior asterisk is never dropped
        assertNull(parsed)
    }

    @Test
    fun `a credited artist is accepted at same name only, never by containment`() {
        // Given - a credit the request does not match whole, one of whose parts it contains
        val combined = "Chuck D, Public Enemy - Album"

        // When - parsing against a longer name holding that part
        val parsed =
            parseDiscogsRelease(combined, requestedArtist = "Public Enemy Number One Crew", requestedTitle = "Album")

        // Then - splitting multiplies the strings compared, so a part is a name or it is nothing
        assertNull(parsed)
    }

    @Test
    fun `an artist whose own name holds a comma is matched whole, before any split`() {
        // Given - a single act whose own name contains the comma Discogs also joins credits with
        val combined = "Earth, Wind & Fire - Album"

        // When - parsing against that act's whole name
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Earth, Wind & Fire", requestedTitle = "Album")

        // Then - the whole credit is tried first, so splitting can never displace a match it has
        assertEquals("Earth, Wind & Fire" to "Album", parsed)
    }
}
