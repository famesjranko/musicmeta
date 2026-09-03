package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The alpha-2 normalisation behind `EnrichmentData.Metadata.country`. Inputs are the shapes the
 * upstreams actually send, plus the alpha-3 form (`USA`) the table is kept for: Discogs' free text
 * (`UK`, `Europe`) and MusicBrainz's alpha-2.
 */
class IsoCountryTest {

    @Test
    fun `alpha2OrNull converts an ISO alpha-3 code to alpha-2`() {
        // Given - an alpha-3 code for the United States
        val raw = "USA"

        // When - normalising it
        val code = IsoCountry.alpha2OrNull(raw)

        // Then - the alpha-2 code for the same country
        assertEquals("US", code)
    }

    @Test
    fun `alpha2OrNull passes an alpha-2 code through unchanged`() {
        // Given - a value that is already an alpha-2 code, as MusicBrainz sends
        val raw = "GB"

        // When - normalising it
        val code = IsoCountry.alpha2OrNull(raw)

        // Then - the same code
        assertEquals("GB", code)
    }

    @Test
    fun `alpha2OrNull reads UK as GB`() {
        // Given - UK, which Discogs writes and ISO does not define
        val raw = "UK"

        // When - normalising it
        val code = IsoCountry.alpha2OrNull(raw)

        // Then - the ISO code for the United Kingdom
        assertEquals("GB", code)
    }

    @Test
    fun `alpha2OrNull converts an English country name to alpha-2`() {
        // Given - a country name of the kind Discogs sends
        val raw = "Germany"

        // When - normalising it
        val code = IsoCountry.alpha2OrNull(raw)

        // Then - that country's alpha-2 code
        assertEquals("DE", code)
    }

    @Test
    fun `alpha2OrNull resolves a name CLDR has since renamed`() {
        // Given - the older spelling upstreams still write, which the JDK now lists as Türkiye
        val raw = "Turkey"

        // When - normalising it
        val code = IsoCountry.alpha2OrNull(raw)

        // Then - the alpha-2 code, so a JDK display-name move cannot silently drop it
        assertEquals("TR", code)
    }

    @Test
    fun `alpha2OrNull yields null for a region that is not a country`() {
        // Given - Discogs' multi-country region labels
        val region = "Europe"
        val otherRegion = "Scandinavia"

        // When - normalising both
        val europe = IsoCountry.alpha2OrNull(region)
        val scandinavia = IsoCountry.alpha2OrNull(otherRegion)

        // Then - nothing, because a region has no country code
        assertNull(europe)
        assertNull(scandinavia)
    }

    @Test
    fun `alpha2OrNull yields null for blank or absent input`() {
        // Given - no country at all, and one that is only whitespace
        val missing: String? = null
        val whitespace = "   "

        // When - normalising both
        val absent = IsoCountry.alpha2OrNull(missing)
        val blank = IsoCountry.alpha2OrNull(whitespace)

        // Then - nothing
        assertNull(absent)
        assertNull(blank)
    }

    @Test
    fun `alpha2OrKeep keeps an unrecognised value instead of dropping it`() {
        // Given - values no country matches: Discogs' region and MusicBrainz's worldwide code
        val region = "Europe"
        val pseudoCode = "XW"

        // When - normalising both on the pass-through path
        val europe = IsoCountry.alpha2OrKeep(region)
        val worldwide = IsoCountry.alpha2OrKeep(pseudoCode)

        // Then - the upstream's own wording survives, since dropping it would lose data
        assertEquals("Europe", europe)
        assertEquals("XW", worldwide)
    }

    @Test
    fun `alpha2OrKeep still normalises a value that names a country`() {
        // Given - values that do name countries, on the pass-through path
        val abbreviation = "UK"
        val name = "Germany"

        // When - normalising both
        val uk = IsoCountry.alpha2OrKeep(abbreviation)
        val germany = IsoCountry.alpha2OrKeep(name)

        // Then - the alpha-2 code, so a country reads the same from every provider
        assertEquals("GB", uk)
        assertEquals("DE", germany)
    }
}
