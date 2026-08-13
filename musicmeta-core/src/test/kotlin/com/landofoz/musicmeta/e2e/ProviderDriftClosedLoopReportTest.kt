package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.provider.lrclib.LrcLibEvidence
import com.landofoz.musicmeta.provider.lrclib.LrcLibSelectionEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-function coverage for the ticket-05 closed-loop trend: title-shape classification, row
 * reduction, aggregation, acceptance-tier diagnostics, and the redaction guarantee — none of this
 * needs a live provider call.
 */
class ProviderDriftClosedLoopReportTest {

    @Test fun `a plain title with no terminal qualifier classifies as PLAIN`() {
        // Given - a title with no bracket or dash qualifier
        val shape = classifyTitleShape("Starman")

        // Then - it is PLAIN
        assertEquals(TitleShape.PLAIN, shape)
    }

    @Test fun `a terminal bracket group classifies as BRACKETED`() {
        // Given - a title with a terminal parenthetical
        val shape = classifyTitleShape("Starman (2012 Remaster)")

        // Then - it is BRACKETED
        assertEquals(TitleShape.BRACKETED, shape)
    }

    @Test fun `a terminal remaster dash qualifier classifies as ALLOWLISTED_DASH_REISSUE`() {
        // Given - a title with a terminal dash-delimited remaster qualifier
        val shape = classifyTitleShape("Starman - 2012 Remaster")

        // Then - it is ALLOWLISTED_DASH_REISSUE
        assertEquals(TitleShape.ALLOWLISTED_DASH_REISSUE, shape)
    }

    @Test fun `a terminal non-remaster dash qualifier classifies as OTHER_DASH`() {
        // Given - a title with a terminal dash-delimited live qualifier
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
            source = RowSource.TOP_TRACKS,
            sourceProvider = "deezer",
            title = "Starman - 2012 Remaster",
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.AMBIGUOUS,
            result = result,
            latencyMs = 120,
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
            source = RowSource.SIMILAR_TRACKS,
            sourceProvider = "lrclib",
            title = "Space Oddity",
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.RESOLVED,
            result = result,
            latencyMs = 80,
        )

        // Then - the printed line contains no lyric text
        assertFalse(redactedLogLine(row).contains("Ground control"))
    }

    @Test fun `an Error result classified with ErrorKind TIMEOUT reports timedOut true`() {
        // Given - an Error result whose errorKind is the engine's own timeout classification
        val result = EnrichmentResult.Error(
            type = EnrichmentType.TRACK_PREVIEW,
            provider = "deezer",
            message = "deadline expired",
            errorKind = ErrorKind.TIMEOUT,
        )

        // When - reducing it to a closed-loop row
        val row = toClosedLoopRow(
            source = RowSource.RADIO,
            sourceProvider = "deezer",
            title = "Starman",
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.UNRESOLVED,
            result = result,
            latencyMs = 5_000,
        )

        // Then - the row reports ERROR and timedOut true
        assertEquals("ERROR", row.outcome)
        assertEquals(true, row.timedOut)
    }

    @Test fun `an Error result classified with a non-timeout ErrorKind reports timedOut false`() {
        // Given - an Error result whose errorKind is not the engine's timeout classification
        val result = EnrichmentResult.Error(
            type = EnrichmentType.TRACK_PREVIEW,
            provider = "deezer",
            message = "malformed response",
            errorKind = ErrorKind.PARSE,
        )

        // When - reducing it to a closed-loop row
        val row = toClosedLoopRow(
            source = RowSource.DISCOGRAPHY,
            sourceProvider = "deezer",
            title = "Starman",
            route = ClosedLoopRoute.NAME,
            canonicalStatus = CanonicalStatus.UNRESOLVED,
            result = result,
            latencyMs = 300,
        )

        // Then - the row reports ERROR but timedOut stays false, distinct from an actual timeout
        assertEquals("ERROR", row.outcome)
        assertEquals(false, row.timedOut)
    }

    @Test fun `a Success result never reports timedOut true`() {
        // Given - a Success result, which carries no errorKind at all
        val result = EnrichmentResult.Success(
            type = EnrichmentType.TRACK_PREVIEW,
            data = EnrichmentData.TrackPreview(url = "https://example.test/p.mp3", durationMs = 30_000, source = "deezer"),
            provider = "deezer",
            confidence = 0.9f,
        )

        // When - reducing it to a closed-loop row
        val row = toClosedLoopRow(
            source = RowSource.TOP_TRACKS,
            sourceProvider = "deezer",
            title = "Starman",
            route = ClosedLoopRoute.EXACT_ID,
            canonicalStatus = CanonicalStatus.RESOLVED,
            result = result,
            latencyMs = 90,
        )

        // Then - timedOut is false
        assertEquals(false, row.timedOut)
    }

    @Test fun `aggregateClosedLoopTrend counts rows by source, provider, shape, route, canonical status and outcome`() {
        // Given - two rows sharing every field and one that differs by outcome
        val shared = ClosedLoopRow(
            source = RowSource.TOP_TRACKS,
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
        val sharedKey = "source=TOP_TRACKS provider=deezer shape=BRACKETED route=NAME canonical=RESOLVED outcome=SUCCESS"
        val differentKey = "source=TOP_TRACKS provider=deezer shape=BRACKETED route=NAME canonical=RESOLVED outcome=NOT_FOUND"
        assertEquals(2, trend[sharedKey])
        assertEquals(1, trend[differentKey])
    }

    @Test fun `toAcceptanceDiagnostic reports the selected artist, title and tier without a raw candidate`() {
        // Given - a selector's title-tier decision for one selected candidate
        val exact = toAcceptanceDiagnostic("David Bowie", "Starman", TitleMatcher.TitleTier.EXACT)
        val editionQualified = toAcceptanceDiagnostic("David Bowie", "Starman - 2012 Remaster", TitleMatcher.TitleTier.EDITION)
        val rejected = toAcceptanceDiagnostic("David Bowie", "Starman (Live Bootleg)", TitleMatcher.TitleTier.NONE)

        // When - classifying each tier
        // Then - EXACT and EDITION map to their own accepted tiers and NONE maps to REJECTED
        assertEquals(AcceptanceTier.EXACT, exact.tier)
        assertEquals(AcceptanceTier.SYNTAX_EQUIVALENT, editionQualified.tier)
        assertEquals(AcceptanceTier.REJECTED, rejected.tier)
    }

    @Test fun `redactedDiagnosticLine for an AcceptanceDiagnostic carries only artist, title and tier`() {
        // Given - a diagnostic for a selected candidate
        val diagnostic = toAcceptanceDiagnostic("David Bowie", "Starman", TitleMatcher.TitleTier.EXACT)

        // When - printing it
        val line = redactedDiagnosticLine(diagnostic)

        // Then - the line names the artist, title and tier and nothing else
        assertEquals("artist=David Bowie title=Starman tier=EXACT", line)
    }

    @Test fun `toLrcLibAcceptanceDiagnostic never carries a raw candidate payload`() {
        // Given - LRCLIB's own album/duration ranking evidence for a selected candidate
        val evidence = LrcLibSelectionEvidence(album = LrcLibEvidence.MATCH, duration = LrcLibEvidence.MISMATCH)

        // When - reducing it to a diagnostic and printing it
        val diagnostic = toLrcLibAcceptanceDiagnostic("David Bowie", "Starman", evidence)
        val line = redactedDiagnosticLine(diagnostic)

        // Then - the line carries only the artist, title and the two evidence classifications
        assertEquals("artist=David Bowie title=Starman album=MATCH duration=MISMATCH", line)
    }
}
