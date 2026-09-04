package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** What a `--types` flag selects, and what an unresolvable name in one does. */
class TypeSelectionTest {

    private fun capture(block: (Terminal) -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            block(Terminal(Theme.Plain))
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `an unknown type name selects nothing and names itself`() {
        // Given - a --types flag carrying a typo for an alias
        val names = extractTypes("artist Radiohead --types boi").second

        // When - selecting the types for an artist request
        var selected: Set<EnrichmentType>? = ALBUM_TYPES
        val output = capture { term -> selected = selectTypes(names, "artist", term) }

        // Then - nothing is selected, so the caller enriches nothing, and the name is reported
        assertNull(selected)
        assertTrue(output, output.contains("Unknown type(s): boi"))
    }

    @Test
    fun `an unknown name alongside a known one still selects nothing`() {
        // Given - a --types flag where only the second name resolves
        val names = extractTypes("artist Radiohead --types boi,bio").second

        // When - selecting the types for an artist request
        var selected: Set<EnrichmentType>? = ALBUM_TYPES
        val output = capture { term -> selected = selectTypes(names, "artist", term) }

        // Then - the partial match is not silently enriched and the discarded name is reported
        assertNull(selected)
        assertTrue(output, output.contains("Unknown type(s): boi"))
    }

    @Test
    fun `an unknown type name never falls back to the default set`() {
        // Given - a --types flag carrying only names that resolve to nothing
        val names = extractTypes("artist Radiohead --types boi qux").second

        // When - selecting the types for an artist request
        var selected: Set<EnrichmentType>? = null
        capture { term -> selected = selectTypes(names, "artist", term) }

        // Then - the full default set is not what an explicit --types resolves to
        assertNull(selected)
    }

    @Test
    fun `no types flag selects the default set for the kind`() {
        // Given - a command with no --types flag at all
        val names = extractTypes("artist Radiohead").second

        // When - selecting the types for an artist request
        var selected: Set<EnrichmentType>? = null
        val output = capture { term -> selected = selectTypes(names, "artist", term) }

        // Then - the artist defaults are selected silently
        assertEquals(ARTIST_TYPES, selected)
        assertEquals("", output)
    }

    @Test
    fun `a resolvable flag selects exactly the named types`() {
        // Given - a --types flag naming two aliases
        val names = extractTypes("artist Radiohead --types bio,photo").second

        // When - selecting the types for an artist request
        var selected: Set<EnrichmentType>? = null
        capture { term -> selected = selectTypes(names, "artist", term) }

        // Then - only those two types are selected
        assertEquals(setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO), selected)
    }

    @Test
    fun `popularity resolves to the popularity type of the kind requested`() {
        // Given - the one alias whose type depends on what was asked for
        val alias = "popularity"

        // When - resolving it against each kind
        val onArtist = resolveType(alias, "artist")
        val onTrack = resolveType(alias, "track")
        val onAlbum = resolveType(alias, "album")

        // Then - each kind gets its own type, and an album has none to give
        assertEquals(EnrichmentType.ARTIST_POPULARITY, onArtist)
        assertEquals(EnrichmentType.TRACK_POPULARITY, onTrack)
        assertNull(onAlbum)
    }

    @Test
    fun `each popularity type has an unambiguous name of its own`() {
        // Given - the two popularity types named explicitly rather than by the bare alias
        val artistName = "artist-popularity"
        val trackName = "track-popularity"

        // When - resolving each against the kind it does not belong to
        val artistOnTrack = resolveType(artistName, "track")
        val trackOnArtist = resolveType(trackName, "artist")

        // Then - the explicit name wins over the kind
        assertEquals(EnrichmentType.ARTIST_POPULARITY, artistOnTrack)
        assertEquals(EnrichmentType.TRACK_POPULARITY, trackOnArtist)
    }

    @Test
    fun `release type and album description have aliases`() {
        // Given - two types reachable only by their full enum name
        val relType = "reltype"
        val description = "desc"

        // When - resolving both on an album request
        val resolvedRelType = resolveType(relType, "album")
        val resolvedDescription = resolveType(description, "album")

        // Then - each resolves to its type
        assertEquals(EnrichmentType.RELEASE_TYPE, resolvedRelType)
        assertEquals(EnrichmentType.ALBUM_DESCRIPTION, resolvedDescription)
    }
}
