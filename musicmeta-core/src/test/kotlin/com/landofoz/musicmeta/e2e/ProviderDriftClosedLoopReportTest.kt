package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-function coverage for the ticket-05 closed-loop trend: title-shape classification, row
 * reduction, aggregation, and the redaction guarantee — none of this needs a live provider call.
 */
class ProviderDriftClosedLoopReportTest {

    @Test fun `a plain title with no terminal qualifier classifies as PLAIN`() {
        // Given - a title with no bracket or dash qualifier
        // When - classifying its shape
        val shape = classifyTitleShape("Starman")

        // Then - it is PLAIN
        assertEquals(TitleShape.PLAIN, shape)
    }

    @Test fun `a terminal bracket group classifies as BRACKETED`() {
        // Given - a title with a terminal parenthetical
        // When - classifying its shape
        val shape = classifyTitleShape("Starman (2012 Remaster)")

        // Then - it is BRACKETED
        assertEquals(TitleShape.BRACKETED, shape)
    }

    @Test fun `a terminal remaster dash qualifier classifies as ALLOWLISTED_DASH_REISSUE`() {
        // Given - a title with a terminal dash-delimited remaster qualifier
        // When - classifying its shape
        val shape = classifyTitleShape("Starman - 2012 Remaster")

        // Then - it is ALLOWLISTED_DASH_REISSUE
        assertEquals(TitleShape.ALLOWLISTED_DASH_REISSUE, shape)
    }

    @Test fun `a terminal non-remaster dash qualifier classifies as OTHER_DASH`() {
        // Given - a title with a terminal dash-delimited live qualifier
        // When - classifying its shape
        val shape = classifyTitleShape("Wish You Were Here - Live")

        // Then - it is OTHER_DASH
        assertEquals(TitleShape.OTHER_DASH, shape)
    }

    @Test fun `toClosedLoopRow never carries the result's data payload`() {
        // Given - a Success result whose data holds a signed preview URL
        val result = EnrichmentResult.Success(
            type = EnrichmentType.TRACK_PREVIEW,
            data = EnrichmentData.TrackPreview(
                url = "https://cdnt-preview.dzcdn.net/api/1/1/secret-signed-token.mp3",
                durationMs = 30_000,
                source = "deezer",
            ),
            provider = "deezer",
            confidence = 0.9f,
        )

        // When - reducing it to a closed-loop row
        val row = toClosedLoopRow(
            sourceProvider = "deezer",
            title = "Starman - 2012 Remaster",
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.AMBIGUOUS,
            result = result,
            latencyMs = 120,
            timedOut = false,
        )

        // Then - the row's own field set has no place to hold the URL, and its printed line
        // does not contain it
        assertEquals("SUCCESS", row.outcome)
        assertFalse(redactedLogLine(row).contains("dzcdn"))
        assertFalse(redactedLogLine(row).contains("secret-signed-token"))
    }

    @Test fun `redactedLogLine never carries lyrics text`() {
        // Given - a Success result whose data holds lyrics content
        val result = EnrichmentResult.Success(
            type = EnrichmentType.LYRICS_PLAIN,
            data = EnrichmentData.Lyrics(plainLyrics = "Ground control to Major Tom"),
            provider = "lrclib",
            confidence = 0.6f,
        )

        // When - reducing it to a closed-loop row and printing it
        val row = toClosedLoopRow(
            sourceProvider = "lrclib",
            title = "Space Oddity",
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.RESOLVED,
            result = result,
            latencyMs = 80,
            timedOut = false,
        )

        // Then - the printed line contains no lyric text
        assertFalse(redactedLogLine(row).contains("Ground control"))
    }

    @Test fun `aggregateClosedLoopTrend counts rows by provider, shape, route, canonical status and outcome`() {
        // Given - two rows sharing every field and one that differs by outcome
        val shared = ClosedLoopRow(
            sourceProvider = "deezer",
            shape = TitleShape.BRACKETED,
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.RESOLVED,
            outcome = "SUCCESS",
            latencyMs = 100,
            timedOut = false,
        )
        val different = shared.copy(outcome = "NOT_FOUND")

        // When - aggregating the trend
        val trend = aggregateClosedLoopTrend(listOf(shared, shared, different))

        // Then - the two shared rows count together and the differing one counts separately
        val sharedKey = "provider=deezer shape=BRACKETED route=NAME canonical=RESOLVED outcome=SUCCESS"
        val differentKey = "provider=deezer shape=BRACKETED route=NAME canonical=RESOLVED outcome=NOT_FOUND"
        assertEquals(2, trend[sharedKey])
        assertEquals(1, trend[differentKey])
    }
}
