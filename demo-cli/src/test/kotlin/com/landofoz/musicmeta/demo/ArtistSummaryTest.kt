package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The artist Profile block, whose `Bio:` row may only claim a truncation it actually made. */
class ArtistSummaryTest {

    private fun artistProfile(bio: String): ArtistProfile = ArtistProfile(
        name = "Radiohead",
        results = resultsOf(
            mapOf<EnrichmentType, EnrichmentResult>(
                EnrichmentType.ARTIST_BIO to EnrichmentResult.Success(
                    type = EnrichmentType.ARTIST_BIO,
                    data = EnrichmentData.Biography(text = bio, source = "lastfm"),
                    provider = "lastfm",
                    confidence = 0.9f,
                ),
            ),
        ),
    )

    /** Only the Profile block — the Results block below it renders its own snippet of the same text. */
    private fun profileBlock(profile: ArtistProfile): String =
        captureOutput { term -> Formatter.printProfile(profile, term) }.substringBefore("Results")

    @Test
    fun `a bio shorter than the snippet is not marked as truncated`() {
        // Given - a biography well inside the snippet width
        val profile = artistProfile("An English rock band.")

        // When - rendering the profile block
        val output = profileBlock(profile)

        // Then - the row carries the whole text and claims no truncation
        assertTrue(output, output.contains("\"An English rock band.\""))
        assertFalse(output, output.contains("..."))
    }

    @Test
    fun `a bio longer than the snippet is marked as truncated`() {
        // Given - a biography well past the snippet width
        val profile = artistProfile("x".repeat(200))

        // When - rendering the profile block
        val output = profileBlock(profile)

        // Then - the row is cut to the snippet width and says so
        assertTrue(output, output.contains("\"${"x".repeat(80)}...\""))
    }

    @Test
    fun `a short bio in the results block is not marked as truncated either`() {
        // Given - a biography well inside the snippet width
        val profile = artistProfile("An English rock band.")

        // When - rendering the results block below the profile
        val output = captureOutput { term -> Formatter.printProfile(profile, term) }.substringAfter("Results")

        // Then - the raw-payload snippet claims no truncation it did not make
        assertFalse(output, output.contains("..."))
    }
}
