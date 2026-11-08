package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.SearchCandidate
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCandidateDisplayTest {
    @Test
    fun `supplement artist typography does not distinguish the primary choice`() {
        // Given - MusicBrainz credits a curly apostrophe while the supplement uses a straight apostrophe.
        val primary = SearchCandidate("Appetite for Destruction", "musicbrainz", EnrichmentIdentifiers(), 1f, artist = "Guns N’ Roses")
        val supplement = primary.copy(provider = "itunes", artist = "Guns N' Roses")

        // When - the supplement is compared with the primary choice.
        val repeats = supplement.repeatsPrimary(primary)

        // Then - the artist spelling does not preserve a duplicate supplement.
        assertTrue(repeats)
    }

    @Test
    fun `missing year on either catalog does not distinguish an edition`() {
        // Given - otherwise identical catalog choices differ only in whether the year is known.
        val dated = SearchCandidate("Album", "musicbrainz", EnrichmentIdentifiers(), 1f, artist = "Artist", year = 2000)
        val undated = dated.copy(provider = "itunes", year = null)

        // When - each catalog is treated as the primary choice in turn.
        val missingSupplementYear = undated.repeatsPrimary(dated)
        val missingPrimaryYear = dated.repeatsPrimary(undated)

        // Then - neither missing year establishes a different edition.
        assertTrue(missingSupplementYear)
        assertTrue(missingPrimaryYear)
    }
}
