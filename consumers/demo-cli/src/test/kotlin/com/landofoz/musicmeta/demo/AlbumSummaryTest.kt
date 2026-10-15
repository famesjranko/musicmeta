package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** The album Profile block, which reads the description through the named accessor. */
class AlbumSummaryTest {

    private fun albumProfile(description: String?): AlbumProfile {
        val raw = buildMap<EnrichmentType, EnrichmentResult> {
            if (description != null) {
                put(
                    EnrichmentType.ALBUM_DESCRIPTION,
                    EnrichmentResult.Success(
                        type = EnrichmentType.ALBUM_DESCRIPTION,
                        data = EnrichmentData.Biography(text = description, source = "wikipedia"),
                        provider = "wikipedia",
                        confidence = 0.9f,
                    ),
                )
            }
        }
        return AlbumProfile(
            title = "OK Computer",
            artist = "Radiohead",
            results = EnrichmentResults(
                raw = raw,
                requestedTypes = setOf(EnrichmentType.ALBUM_DESCRIPTION),
                identity = IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.RESOLVED),
            ),
        )
    }

    private fun render(profile: AlbumProfile): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            Formatter.printProfile(profile, Terminal(Theme.Plain))
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    /** Only the Profile block — the Results block below it lists every type's raw payload. */
    private fun profileBlock(output: String): String = output.substringBefore("Results")

    @Test
    fun `a description is rendered as its own profile row`() {
        // Given - an album whose results carry an ALBUM_DESCRIPTION
        val profile = albumProfile("A 1997 album by the English rock band Radiohead.")

        // When - rendering the profile block
        val output = profileBlock(render(profile))

        // Then - the description appears under its own label
        assertTrue(output, output.contains("Description:"))
        assertTrue(output, output.contains("A 1997 album by the English rock band Radiohead."))
    }

    @Test
    fun `a description longer than the snippet is marked as truncated`() {
        // Given - an album description well past the snippet width
        val profile = albumProfile("x".repeat(200))

        // When - rendering the profile block
        val output = profileBlock(render(profile))

        // Then - the row is cut to the snippet width and says so
        assertTrue(output, output.contains("\"${"x".repeat(80)}...\""))
    }

    @Test
    fun `no description means no description row`() {
        // Given - an album whose results carry no ALBUM_DESCRIPTION
        val profile = albumProfile(null)

        // When - rendering the profile block
        val output = profileBlock(render(profile))

        // Then - the row is absent rather than empty
        assertFalse(output, output.contains("Description:"))
    }
}
