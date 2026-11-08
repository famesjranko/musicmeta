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
    fun `supplied artist's punctuation variant precedes another artist's exact title`() = runTest {
        // Given - The Beatles' punctuated title competes with Bride's exact title.
        http.givenJsonResponse("recording?query", punctuationFixture("help-punctuation"))

        // When - the caller supplies The Beatles for the bare Help title.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Help", "The Beatles"), 2)

        // Then - The Beatles' punctuation variant leads the other artist's exact title.
        assertEquals(
            listOf("fa2b2af5-3ba2-4155-b1d9-45066322edc5", "73f6f089-eda1-4092-87e7-8ae699000bd6"),
            found.map { it.identifiers.musicBrainzId },
        )
    }

    @Test
    fun `supplied ABBA punctuation recording precedes a tribute's exact title`() = runTest {
        // Given - ABBA's punctuated recording competes with a tribute act's exact title.
        http.givenJsonResponse("recording?query", punctuationFixture("sos"))

        // When - the caller supplies ABBA for the bare SOS title.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("SOS", "ABBA"), 2)

        // Then - ABBA's punctuation variant leads Bootleg ABBA's exact title.
        assertEquals(
            listOf("31e2ea49-a493-4529-a64e-c1fa948cffcf", "3afebdda-23b3-4ead-9f2d-4144666234d2"),
            found.map { it.identifiers.musicBrainzId },
        )
    }

    @Test
    fun `supplied artist's exact title precedes its own punctuation variant`() = runTest {
        // Given - The Beatles have both exact and punctuation variants of the requested title.
        http.givenJsonResponse("recording?query", punctuationFixture("help-exact"))

        // When - the caller supplies The Beatles for the bare Help title.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Help", "The Beatles"), 2)

        // Then - the exact title leads its punctuation variant.
        assertEquals(
            listOf("f9c045cc-6703-4787-8145-8a29d4afd243", "fa2b2af5-3ba2-4155-b1d9-45066322edc5"),
            found.map { it.identifiers.musicBrainzId },
        )
    }

    @Test
    fun `title-only search keeps another artist's exact title ahead of a hinted punctuation variant`() = runTest {
        // Given - a title-only pool and an iTunes-style hint for The Beatles.
        http.givenJsonResponse("recording?query", punctuationFixture("help-punctuation"))

        // When - the title-only search receives The Beatles as its first artist hint.
        val found = provider.searchCandidates(
            EnrichmentRequest.forTrack("Help", ""), 2,
            listOf("The Beatles"), EnrichmentLogger.NoOp,
        )

        // Then - the other artist's exact title keeps its existing lead.
        assertEquals("73f6f089-eda1-4092-87e7-8ae699000bd6", found.first().identifiers.musicBrainzId)
    }

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

    private fun punctuationFixture(name: String): String =
        requireNotNull(javaClass.getResource("/pools/musicbrainz-punctuation-ranking/$name.json")).readText()
}
