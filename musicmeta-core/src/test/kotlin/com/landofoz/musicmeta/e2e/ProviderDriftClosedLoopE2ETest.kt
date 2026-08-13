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
 * One provider-produced title sampled for the closed loop: [deezerId] is `null` when the source
 * row carried no Deezer identifier, in which case only the [ClosedLoopRoute.NAME] route runs.
 */
private data class SampleTitle(val title: String, val artist: String, val deezerId: String?)

/**
 * Ticket 05's closed loop: a bounded, deterministic sample of provider-produced titles from Top
 * Tracks, radio, similar tracks and discography, fed back through one representative name-search
 * enrichment type (`TRACK_PREVIEW`), reported as a dated trend by row source, source provider,
 * title shape, route, canonical status and outcome. Trend-only — no assertion here treats a
 * `NotFound`, a shape shift, or a timeout as a failure; the hard suppression invariant (a
 * suggestions-carrying identity must not suppress an eligible provider) is covered directly by
 * [com.landofoz.musicmeta.engine.IdentitySuggestionFanOutTest], which needs no live network and
 * therefore runs on every build rather than once daily.
 *
 * `sourceProvider` is read from each listing call's own [EnrichmentResult.Success.provider] rather
 * than assumed, so the trend stays correct if a listing type ever gains a second capable provider.
 * `timedOut` is read from the feedback call's own [com.landofoz.musicmeta.ErrorKind.TIMEOUT], not
 * inferred from latency.
 *
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*ProviderDriftClosedLoop*"
 */
class ProviderDriftClosedLoopE2ETest {

    private lateinit var engine: EnrichmentEngine

    /** Bounded per source: a fixed cap keeps the daily sample small and the trend comparable run to run. */
    private val sampleSize = 4

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
    fun `bounded deterministic multi-source sample feeds back through TRACK_PREVIEW and reports a dated trend`() =
        runBlocking {
            // Given - a deterministic, capped sample of one stable artist's provider-produced titles
            // from each of the four listings the ticket names
            val artistRequest = EnrichmentRequest.forArtist("Radiohead")
            val sources = listOf(
                RowSource.TOP_TRACKS to topTracksSample(artistRequest),
                RowSource.RADIO to radioSample(artistRequest),
                RowSource.SIMILAR_TRACKS to similarTracksSample(artistRequest),
                RowSource.DISCOGRAPHY to discographySample(artistRequest),
            )
            Assume.assumeTrue(
                "no sample rows from any source this run",
                sources.any { (_, sampled) -> sampled != null && sampled.titles.isNotEmpty() },
            )

            // When - each sampled title is fed back through TRACK_PREVIEW by name, and again by its
            // Deezer id when the row carries one, so both routes stay visible per the ticket
            val rows = mutableListOf<ClosedLoopRow>()
            for ((source, sampled) in sources) {
                if (sampled == null) continue
                for (title in sampled.titles) {
                    val nameStarted = System.currentTimeMillis()
                    val nameRun = engine.enrich(EnrichmentRequest.forTrack(title.title, title.artist), setOf(EnrichmentType.TRACK_PREVIEW))
                    val nameResult = nameRun.raw[EnrichmentType.TRACK_PREVIEW]
                    if (nameResult != null) {
                        rows += toClosedLoopRow(
                            source = source,
                            sourceProvider = sampled.provider,
                            title = title.title,
                            route = ClosedLoopRoute.NAME,
                            canonicalStatus = nameRun.identity.status,
                            result = nameResult,
                            latencyMs = System.currentTimeMillis() - nameStarted,
                        )
                    }

                    if (title.deezerId != null) {
                        val idStarted = System.currentTimeMillis()
                        val idRequest = EnrichmentRequest.forTrack(
                            title.title,
                            title.artist,
                            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, title.deezerId),
                        )
                        val idRun = engine.enrich(idRequest, setOf(EnrichmentType.TRACK_PREVIEW))
                        val idResult = idRun.raw[EnrichmentType.TRACK_PREVIEW]
                        if (idResult != null) {
                            rows += toClosedLoopRow(
                                source = source,
                                sourceProvider = sampled.provider,
                                title = title.title,
                                route = ClosedLoopRoute.EXACT_ID,
                                canonicalStatus = idRun.identity.status,
                                result = idResult,
                                latencyMs = System.currentTimeMillis() - idStarted,
                            )
                        }
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

    /** One listing call's provider-attributed sample: [provider] is the listing's own answering provider. */
    private data class SampledListing(val provider: String, val titles: List<SampleTitle>)

    private suspend fun topTracksSample(artistRequest: EnrichmentRequest.ForArtist): SampledListing? {
        val result = engine.enrich(artistRequest, setOf(EnrichmentType.ARTIST_TOP_TRACKS)).raw[EnrichmentType.ARTIST_TOP_TRACKS]
        val success = result as? EnrichmentResult.Success ?: return null
        val data = success.data as? EnrichmentData.TopTracks ?: return null
        val titles = data.tracks.sortedBy { it.rank }.take(sampleSize)
            .map { SampleTitle(it.title, it.artist, it.identifiers.get(IdentifierNamespace.DEEZER)) }
        return SampledListing(success.provider, titles)
    }

    private suspend fun radioSample(artistRequest: EnrichmentRequest.ForArtist): SampledListing? {
        val result = engine.enrich(artistRequest, setOf(EnrichmentType.ARTIST_RADIO)).raw[EnrichmentType.ARTIST_RADIO]
        val success = result as? EnrichmentResult.Success ?: return null
        val data = success.data as? EnrichmentData.RadioPlaylist ?: return null
        val titles = data.tracks.take(sampleSize)
            .map { SampleTitle(it.title, it.artist, it.identifiers.get(IdentifierNamespace.DEEZER)) }
        return SampledListing(success.provider, titles)
    }

    private suspend fun similarTracksSample(artistRequest: EnrichmentRequest.ForArtist): SampledListing? {
        val result = engine.enrich(artistRequest, setOf(EnrichmentType.SIMILAR_TRACKS)).raw[EnrichmentType.SIMILAR_TRACKS]
        val success = result as? EnrichmentResult.Success ?: return null
        val data = success.data as? EnrichmentData.SimilarTracks ?: return null
        val titles = data.tracks.take(sampleSize)
            .map { SampleTitle(it.title, it.artist, it.identifiers.get(IdentifierNamespace.DEEZER)) }
        return SampledListing(success.provider, titles)
    }

    /**
     * Discography rows are albums, not tracks, and carry no artist field of their own (a single
     * artist's own discography) — the requested artist stands in for it. Feeding an album title
     * through `TRACK_PREVIEW` widens the sampled decoration shapes; it is not expected to preview.
     */
    private suspend fun discographySample(artistRequest: EnrichmentRequest.ForArtist): SampledListing? {
        val result = engine.enrich(artistRequest, setOf(EnrichmentType.ARTIST_DISCOGRAPHY)).raw[EnrichmentType.ARTIST_DISCOGRAPHY]
        val success = result as? EnrichmentResult.Success ?: return null
        val data = success.data as? EnrichmentData.Discography ?: return null
        val titles = data.albums.take(sampleSize)
            .map { SampleTitle(it.title, artistRequest.name, it.identifiers.get(IdentifierNamespace.DEEZER)) }
        return SampledListing(success.provider, titles)
    }
}
