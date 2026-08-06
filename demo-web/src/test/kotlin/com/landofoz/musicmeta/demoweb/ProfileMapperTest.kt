package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.SearchCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileMapperTest {

    @Test
    fun `did-you-mean section dedupes candidates that render identically`() {
        // Given — three SUGGESTIONS candidates that differ only by an identifier the row never
        // renders (release MBID), so two of them would otherwise show as indistinguishable dupes.
        val suggestions = listOf(
            SearchCandidate(
                title = "Master of Puppets",
                artist = "Metallica",
                year = "1986",
                country = "US",
                releaseType = "Album",
                score = 90,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(musicBrainzId = "release-1"),
                provider = "musicbrainz",
            ),
            SearchCandidate(
                title = "Master of Puppets",
                artist = "Metallica",
                year = "1986",
                country = "US",
                releaseType = "Album",
                score = 88,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(musicBrainzId = "release-2"),
                provider = "musicbrainz",
            ),
            SearchCandidate(
                title = "Master of Puppets (Remastered)",
                artist = "Metallica",
                year = "2017",
                country = "US",
                releaseType = "Album",
                score = 80,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(musicBrainzId = "release-3"),
                provider = "musicbrainz",
            ),
        )
        val results = EnrichmentResults(
            raw = emptyMap(),
            requestedTypes = emptySet(),
            identity = IdentityResolution(
                identifiers = EnrichmentIdentifiers(),
                match = IdentityMatch.SUGGESTIONS,
                matchScore = null,
                suggestions = suggestions,
            ),
        )
        val profile = AlbumProfile(title = "Master of Pupets", artist = "Metalica", results = results)

        // When
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then — the two identical-looking rows collapse to one, keeping the higher-ranked first
        val section = response.sections.first { it.key == "did_you_mean" }
        assertEquals(2, section.items.size)
        assertEquals("Master of Puppets", section.items[0].primary)
        assertEquals("Master of Puppets (Remastered)", section.items[1].primary)
    }

    @Test
    fun `did-you-mean section absent when no suggestions`() {
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null)
        val profile = AlbumProfile(title = "OK Computer", artist = "Radiohead", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertNull(response.sections.firstOrNull { it.key == "did_you_mean" })
    }
}
