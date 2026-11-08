package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzCandidateArtistRankingTest {
    private val http = FakeHttpClient()
    private val provider = MusicBrainzProvider(http, RateLimiter(0))

    @Test
    fun `supplied track artist outranks an unmarked cover while exact title stays first`() = runTest {
        // Given - the requested collaboration is live and competes with an unmarked cover and a qualified title.
        http.givenJsonResponse("comment", fixture("canonical-tracks"))
        http.givenJsonResponse("recording?query", fixture("supplied-tracks"))

        // When - the caller supplies Queen as the artist.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Under Pressure", "Queen"), 3)

        // Then - the requested artist leads the exact titles and the qualified title remains last.
        assertEquals(listOf("Queen & David Bowie", "Joss Stone", "Queen & David Bowie"), found.map { it.artist })
        assertEquals(listOf("Under Pressure", "Under Pressure", "Under Pressure (Live)"), found.map { it.title })
        assertTrue(found.first().disambiguation?.startsWith("live") == true)
    }

    @Test
    fun `supplied album artist outranks an unmarked cover`() = runTest {
        // Given - the requested collaboration has a disambiguation and a different artist's album has none.
        http.givenJsonResponse("release?query", fixture("supplied-albums"))

        // When - the caller supplies Queen as the album artist.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Under Pressure", "Queen"), 2)

        // Then - the requested artist leads and the other album remains selectable.
        assertEquals(listOf("Queen & David Bowie", "Joss Stone"), found.map { it.artist })
        assertTrue(found.first().disambiguation?.startsWith("live") == true)
    }

    @Test
    fun `popularity hint matches an individual artist in a collaboration`() = runTest {
        // Given - David Bowie leads Joss Stone in the hints and is the second artist in the collaboration.
        http.givenJsonResponse("recording?query", fixture("tied-tracks"))

        // When - the title-only pool is ranked with the ordered artist hints.
        val found = provider.searchCandidates(
            EnrichmentRequest.forTrack("Under Pressure", ""), 2,
            listOf("David Bowie", "Joss Stone"), EnrichmentLogger.NoOp,
        )

        // Then - the second structured artist gets the hint without changing the displayed collaboration.
        assertEquals(listOf("Queen & David Bowie", "Joss Stone"), found.map { it.artist })
    }

    @Test
    fun `popularity hint does not treat a tribute name as the credited artist`() = runTest {
        // Given - Killer Queen sorts alphabetically before the real Queen collaboration when neither gets the hint.
        http.givenJsonResponse("recording?query", fixture("tribute-tracks"))

        // When - Queen is the artist hint for the title-only search.
        val found = provider.searchCandidates(
            EnrichmentRequest.forTrack("Under Pressure", ""), 2,
            listOf("Queen"), EnrichmentLogger.NoOp,
        )

        // Then - only the individually credited Queen gets the hint and the tribute stays selectable.
        assertEquals(listOf("Queen & David Bowie", "Killer Queen"), found.map { it.artist })
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/find-ranking-review/$name.json")).readText()
}
