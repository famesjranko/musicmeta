package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.TrackProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The track Profile block, which reads duration and album title from `TrackProfile.trackMetadata`. */
class TrackSummaryTest {

    private fun trackProfile(metadata: EnrichmentData.TrackMetadata?): TrackProfile {
        val raw = buildMap<EnrichmentType, EnrichmentResult> {
            if (metadata != null) {
                put(
                    EnrichmentType.TRACK_METADATA,
                    EnrichmentResult.Success(
                        type = EnrichmentType.TRACK_METADATA,
                        data = metadata,
                        provider = "musicbrainz",
                        confidence = 0.9f,
                    ),
                )
            }
        }
        return TrackProfile(
            title = "Paranoid Android",
            artist = "Radiohead",
            results = resultsOf(raw, requestedTypes = setOf(EnrichmentType.TRACK_METADATA)),
        )
    }

    /** Only the Profile block — the Results block below it lists every type's raw payload. */
    private fun profileBlock(profile: TrackProfile): String =
        captureOutput { term -> Formatter.printProfile(profile, term) }.substringBefore("Results")

    @Test
    fun `a duration is rendered as its own profile row in minutes and seconds`() {
        // Given - a track whose results carry a TRACK_METADATA duration
        val profile = trackProfile(EnrichmentData.TrackMetadata(durationMs = 383_000))

        // When - rendering the profile block
        val output = profileBlock(profile)

        // Then - the duration appears under its own label, read as a clock time
        assertTrue(output, output.contains("Duration:"))
        assertTrue(output, output.contains("6:23"))
    }

    @Test
    fun `an album title is rendered as its own profile row`() {
        // Given - a track whose results name the release it came from
        val profile = trackProfile(EnrichmentData.TrackMetadata(albumTitle = "OK Computer"))

        // When - rendering the profile block
        val output = profileBlock(profile)

        // Then - the album appears under its own label
        assertTrue(output, output.contains("Album:"))
        assertTrue(output, output.contains("OK Computer"))
    }

    @Test
    fun `a metadata payload missing a field renders no row for it`() {
        // Given - track metadata carrying a duration and no album title
        val profile = trackProfile(EnrichmentData.TrackMetadata(durationMs = 60_000))

        // When - rendering the profile block
        val output = profileBlock(profile)

        // Then - the row that has no value is absent rather than empty
        assertTrue(output, output.contains("Duration:"))
        assertFalse(output, output.contains("Album:"))
    }

    @Test
    fun `no track metadata means neither row`() {
        // Given - a track whose results carry no TRACK_METADATA at all
        val profile = trackProfile(null)

        // When - rendering the profile block
        val output = profileBlock(profile)

        // Then - both rows are absent
        assertFalse(output, output.contains("Duration:"))
        assertFalse(output, output.contains("Album:"))
    }
}
