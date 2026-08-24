package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.TopTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A [TopTrack]'s own identifiers must survive into the "Top Tracks" section's play button
 * ([SectionItem.identifiers]) and its internal-navigation target ([EnrichTarget.identifiers]):
 * without them, a row that already knows its Deezer track id would feed `/api/preview`/`/api/enrich`
 * an ambiguous name search instead.
 */
class TopTrackIdentifierMappingTest {

    private fun resultsOf(topTracks: List<TopTrack>): EnrichmentResults = EnrichmentResults(
        raw = mapOf(
            EnrichmentType.ARTIST_TOP_TRACKS to EnrichmentResult.Success(
                EnrichmentType.ARTIST_TOP_TRACKS,
                EnrichmentData.TopTracks(topTracks),
                provider = "test",
                confidence = 1.0f,
            ),
        ),
        requestedTypes = setOf(EnrichmentType.ARTIST_TOP_TRACKS),
        identity = IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED),
    )

    @Test fun `a top track carrying a Deezer id survives into the section item and its enrich target`() {
        // Given - an artist whose top track carries a Deezer track id and a recording MBID
        val track = TopTrack(
            title = "Starman",
            artist = "David Bowie",
            album = "Aladdin Sane",
            rank = 1,
            identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-starman")
                .with(IdentifierNamespace.DEEZER, "107471926"),
        )
        val profile = ArtistProfile("David Bowie", resultsOf(listOf(track)))

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - both the play-button identifiers and the enrich-navigation identifiers carry the
        // exact same identity the TopTrack came in with
        val item = response.sections.first { it.key == "top_tracks" }.items.single()
        val expected = WireIdentifiers(
            entityKind = WireEntityKind.TRACK,
            musicBrainzId = "mbid-starman",
            deezerTrackId = "107471926",
        )
        assertEquals(expected, item.identifiers)
        assertEquals(expected, item.enrich?.identifiers)
    }

    @Test fun `a top track with no identifiers of its own uses names only`() {
        // Given - a top track from a source that never resolved any identifier
        val track = TopTrack(title = "Life on Mars?", artist = "David Bowie", rank = 1)
        val profile = ArtistProfile("David Bowie", resultsOf(listOf(track)))

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - identifiers are absent everywhere, so the row remains name-based
        val item = response.sections.first { it.key == "top_tracks" }.items.single()
        assertNull(item.identifiers)
        assertNull(item.enrich?.identifiers)
    }
}
