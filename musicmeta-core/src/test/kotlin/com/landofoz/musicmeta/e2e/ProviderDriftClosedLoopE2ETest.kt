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
 * One provider-produced title sampled for the closed loop. [deezerId] is `null` when the source
 * row carried no Deezer identifier, in which case only the [ClosedLoopRoute.NAME] route runs.
 * Its entity scope matches [RowSource]: a track id for every source except
 * [RowSource.DISCOGRAPHY], whose rows are albums and so carry an album id — [feedBack] is what
 * keeps that id attached only to the request kind it actually scopes to.
 */
private data class SampleTitle(
    val title: String,
    val artist: String,
    val sourceProvider: String,
    val deezerId: String?,
)

/**
 * A bounded, deterministic sample of provider-produced titles from Top Tracks, radio, similar
 * tracks and discography, fed back through one representative name-search
 * enrichment type per entity kind — `TRACK_PREVIEW` for tracks, `ALBUM_METADATA` for discography's
 * albums — reported as a dated trend by row source, source provider, title shape, route, canonical
 * status and outcome. Trend-only — no assertion here treats a `NotFound`, a shape shift, or a
 * timeout as a failure; the hard suppression invariant (a suggestions-carrying identity must not
 * suppress an eligible provider) is covered directly by
 * [com.landofoz.musicmeta.engine.IdentitySuggestionFanOutTest], which needs no live network and
 * therefore runs on every build rather than once daily.
 *
 * `sourceProvider` is [sourceProviderFor]'s reduction of each row's own contributing sources, not
 * assumed, so the trend stays correct as merge membership changes. `timedOut` is read from the
 * feedback call's own [com.landofoz.musicmeta.ErrorKind.TIMEOUT], not inferred from latency.
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
    fun `bounded deterministic multi-source sample feeds back through an entity-appropriate type and reports a dated trend`() =
        runBlocking {
            // Given - a deterministic, capped sample of one stable artist's provider-produced titles
            // from each of the four configured listings, with similar tracks seeded from a
            // sampled track since its shipped providers require a track-scoped request
            val artistRequest = EnrichmentRequest.forArtist("Radiohead")
            val topTracks = topTracksSample(artistRequest)
            val radio = radioSample(artistRequest)
            val discography = discographySample(artistRequest)
            val similarSeed = topTracks.firstOrNull() ?: radio.firstOrNull()
            val similarTracks = similarSeed?.let { similarTracksSample(it) }.orEmpty()
            val sources = listOf(
                RowSource.TOP_TRACKS to topTracks,
                RowSource.RADIO to radio,
                RowSource.SIMILAR_TRACKS to similarTracks,
                RowSource.DISCOGRAPHY to discography,
            )
            sources.forEach { (source, titles) ->
                println("  ${sourceAvailabilityLine(source, titles.size)}")
            }
            Assume.assumeTrue(
                "no sample rows from any source this run",
                sources.any { (_, titles) -> titles.isNotEmpty() },
            )

            // When - each sampled title is fed back by name, and again by its Deezer id when the row
            // carries one, through the enrichment type and request kind its own entity scopes to
            val rows = mutableListOf<ClosedLoopRow>()
            for ((source, titles) in sources) {
                for (title in titles) {
                    rows += feedBack(source, title)
                }
            }

            // Then - a dated, redacted trend is printed for the maintainer reading this run's log; no
            // assertion treats any individual outcome as a failure
            println("provider-drift closed-loop trend ${LocalDate.now()}")
            aggregateClosedLoopTrend(rows).toSortedMap().forEach { (key, count) -> println("  $key count=$count") }
            rows.forEach { println("  " + redactedLogLine(it)) }
            assertTrue("expected at least one sampled row to have been fed back", rows.isNotEmpty())
        }

    /**
     * Feeds [title] back through the enrichment type and request kind [source]'s entity scopes to:
     * discography's albums through `ALBUM_METADATA`/[EnrichmentRequest.ForAlbum], every other
     * source's tracks through `TRACK_PREVIEW`/[EnrichmentRequest.ForTrack]. [title.deezerId] is
     * attached only to the request kind matching its own scope, so an album id can never be sent as
     * a track id or vice versa.
     */
    private suspend fun feedBack(source: RowSource, title: SampleTitle): List<ClosedLoopRow> {
        val rows = mutableListOf<ClosedLoopRow>()
        val type = if (source == RowSource.DISCOGRAPHY) EnrichmentType.ALBUM_METADATA else EnrichmentType.TRACK_PREVIEW

        val nameStarted = System.currentTimeMillis()
        val nameRequest = if (source == RowSource.DISCOGRAPHY) {
            EnrichmentRequest.forAlbum(title.title, title.artist)
        } else {
            EnrichmentRequest.forTrack(title.title, title.artist)
        }
        val nameRun = engine.enrich(nameRequest, setOf(type))
        val nameResult = nameRun.raw[type]
        if (nameResult != null) {
            rows += toClosedLoopRow(
                source = source,
                sourceProvider = title.sourceProvider,
                title = title.title,
                route = ClosedLoopRoute.NAME,
                canonicalStatus = nameRun.identity.status,
                result = nameResult,
                latencyMs = System.currentTimeMillis() - nameStarted,
            )
        }

        if (title.deezerId != null) {
            val idStarted = System.currentTimeMillis()
            val idIdentifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, title.deezerId)
            val idRequest = if (source == RowSource.DISCOGRAPHY) {
                EnrichmentRequest.forAlbum(title.title, title.artist, identifiers = idIdentifiers)
            } else {
                EnrichmentRequest.forTrack(title.title, title.artist, identifiers = idIdentifiers)
            }
            val idRun = engine.enrich(idRequest, setOf(type))
            val idResult = idRun.raw[type]
            if (idResult != null) {
                rows += toClosedLoopRow(
                    source = source,
                    sourceProvider = title.sourceProvider,
                    title = title.title,
                    route = ClosedLoopRoute.EXACT_ID,
                    canonicalStatus = idRun.identity.status,
                    result = idResult,
                    latencyMs = System.currentTimeMillis() - idStarted,
                )
            }
        }
        return rows
    }

    /** Fetches [type] for [request], and maps [items]'s first [sampleSize] entries via [toSampleTitle]. */
    private suspend fun <T> sampleListing(
        request: EnrichmentRequest,
        type: EnrichmentType,
        items: (EnrichmentData) -> List<T>?,
        toSampleTitle: (T, String) -> SampleTitle,
    ): List<SampleTitle> {
        val result = engine.enrich(request, setOf(type)).raw[type]
        val success = result as? EnrichmentResult.Success ?: return emptyList()
        val data = items(success.data) ?: return emptyList()
        return data.take(sampleSize).map { toSampleTitle(it, success.provider) }
    }

    private suspend fun topTracksSample(artistRequest: EnrichmentRequest.ForArtist): List<SampleTitle> =
        sampleListing(
            artistRequest,
            EnrichmentType.ARTIST_TOP_TRACKS,
            { (it as? EnrichmentData.TopTracks)?.tracks?.sortedBy { track -> track.rank } },
        ) { track, listingProvider ->
            SampleTitle(
                track.title,
                track.artist,
                sourceProviderFor(track.sources, listingProvider),
                track.identifiers.get(IdentifierNamespace.DEEZER),
            )
        }

    private suspend fun radioSample(artistRequest: EnrichmentRequest.ForArtist): List<SampleTitle> =
        sampleListing(
            artistRequest,
            EnrichmentType.ARTIST_RADIO,
            { (it as? EnrichmentData.RadioPlaylist)?.tracks },
        ) { track, listingProvider ->
            SampleTitle(track.title, track.artist, listingProvider, track.identifiers.get(IdentifierNamespace.DEEZER))
        }

    /**
     * Similar tracks require a [EnrichmentRequest.ForTrack] request — every shipped similar-tracks
     * provider seeds its results from one track, not an artist — so this samples from [seed], one
     * already-sampled track, rather than requesting by artist.
     */
    private suspend fun similarTracksSample(seed: SampleTitle): List<SampleTitle> =
        sampleListing(
            EnrichmentRequest.forTrack(seed.title, seed.artist),
            EnrichmentType.SIMILAR_TRACKS,
            { (it as? EnrichmentData.SimilarTracks)?.tracks },
        ) { track, listingProvider ->
            SampleTitle(
                track.title,
                track.artist,
                sourceProviderFor(track.sources, listingProvider),
                track.identifiers.get(IdentifierNamespace.DEEZER),
            )
        }

    /**
     * Discography rows are albums, not tracks, and carry no artist field of their own (a single
     * artist's own discography) — the requested artist stands in for it. Album identifiers are
     * intentionally not fed back here: the current identity scope does not establish a Deezer
     * album-id route, so album samples stay on the name path.
     */
    private suspend fun discographySample(artistRequest: EnrichmentRequest.ForArtist): List<SampleTitle> =
        sampleListing(
            artistRequest,
            EnrichmentType.ARTIST_DISCOGRAPHY,
            { (it as? EnrichmentData.Discography)?.albums },
        ) { album, listingProvider ->
            SampleTitle(album.title, artistRequest.name, listingProvider, null)
        }
}
