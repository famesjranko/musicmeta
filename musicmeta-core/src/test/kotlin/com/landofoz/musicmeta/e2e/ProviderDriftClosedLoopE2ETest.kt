package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Ticket 05's closed loop: a bounded, deterministic sample of provider-produced Top Tracks titles,
 * fed back through one representative name-search enrichment type (`TRACK_PREVIEW`), reported as a
 * dated trend by source provider, title shape, route, and canonical status. Trend-only — no
 * assertion here treats a `NotFound` or a shape shift as a failure; the hard suppression invariant
 * (a suggestions-carrying identity must not suppress an eligible provider) is covered directly by
 * [com.landofoz.musicmeta.engine.IdentitySuggestionFanOutTest], which needs no live network and
 * therefore runs on every build rather than once daily.
 *
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*ProviderDriftClosedLoop*"
 */
class ProviderDriftClosedLoopE2ETest {

    private lateinit var engine: EnrichmentEngine

    /** Bounded: a fixed cap keeps the daily sample small and the trend comparable run to run. */
    private val sampleSize = 8

    @Before
    fun setup() {
        Assume.assumeTrue(
            "E2E tests disabled. Run with -Dinclude.e2e=true",
            System.getProperty("include.e2e") == "true",
        )
        val f = E2ETestFixture
        engine = EnrichmentEngine.Builder()
            .addProvider(MusicBrainzProvider(f.httpClient, f.mbRateLimiter))
            .addProvider(DeezerProvider(f.httpClient, f.defaultRateLimiter))
            .addProvider(LrcLibProvider(f.httpClient, f.lrcLibRateLimiter))
            .build()
    }

    @Test
    fun `bounded deterministic Top Tracks sample feeds back through TRACK_PREVIEW and reports a dated trend`() =
        runBlocking {
            // Given - a deterministic, rank-ordered, capped sample of one stable artist's provider-produced
            // Top Tracks titles
            val artistRequest = EnrichmentRequest.forArtist("Radiohead")
            val topTracksResult = engine.enrich(artistRequest, setOf(EnrichmentType.ARTIST_TOP_TRACKS))
                .raw[EnrichmentType.ARTIST_TOP_TRACKS]
            val topTracks = (topTracksResult as? EnrichmentResult.Success)?.data as? EnrichmentData.TopTracks
            Assume.assumeTrue("ARTIST_TOP_TRACKS unavailable this run", topTracks != null)
            val sample = topTracks!!.tracks.sortedBy { it.rank }.take(sampleSize)
            Assume.assumeTrue("no Top Tracks rows to sample this run", sample.isNotEmpty())

            // When - each sampled title is fed back through TRACK_PREVIEW by name, and again by its
            // Deezer id when the row carries one, so both routes stay visible per the ticket
            val rows = mutableListOf<ClosedLoopRow>()
            for (track in sample) {
                val nameStarted = System.currentTimeMillis()
                val nameRun = engine.enrich(EnrichmentRequest.forTrack(track.title, track.artist), setOf(EnrichmentType.TRACK_PREVIEW))
                val nameResult = nameRun.raw[EnrichmentType.TRACK_PREVIEW]
                if (nameResult != null) {
                    rows += toClosedLoopRow(
                        sourceProvider = "deezer",
                        title = track.title,
                        route = ClosedLoopRoute.NAME,
                        canonicalStatus = nameRun.identity.status,
                        result = nameResult,
                        latencyMs = System.currentTimeMillis() - nameStarted,
                        timedOut = false,
                    )
                }

                val deezerId = track.identifiers.get(IdentifierNamespace.DEEZER)
                if (deezerId != null) {
                    val idStarted = System.currentTimeMillis()
                    val idRequest = EnrichmentRequest.forTrack(
                        track.title,
                        track.artist,
                        identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, deezerId),
                    )
                    val idRun = engine.enrich(idRequest, setOf(EnrichmentType.TRACK_PREVIEW))
                    val idResult = idRun.raw[EnrichmentType.TRACK_PREVIEW]
                    if (idResult != null) {
                        rows += toClosedLoopRow(
                            sourceProvider = "deezer",
                            title = track.title,
                            route = ClosedLoopRoute.EXACT_ID,
                            canonicalStatus = idRun.identity.status,
                            result = idResult,
                            latencyMs = System.currentTimeMillis() - idStarted,
                            timedOut = false,
                        )
                    }
                }
            }

            // Then - a dated, redacted trend is printed for the maintainer reading this run's log; no
            // assertion treats any individual outcome as a failure
            println("provider-drift closed-loop trend ${LocalDate.now()}")
            aggregateClosedLoopTrend(rows).toSortedMap().forEach { (key, count) -> println("  $key count=$count") }
            rows.forEach { println("  " + redactedLogLine(it)) }
            assertTrue("expected at least one sampled row to have been fed back through TRACK_PREVIEW", rows.isNotEmpty())
        }
}
