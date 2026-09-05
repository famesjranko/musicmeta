package com.landofoz.musicmeta.probe

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * The concurrency addendum to the finished breaker-key A/B: the A/B's S1 workload driven through
 * the real `enrich()` fan-out instead of a sequential walk over `chainFor(type)`, to measure
 * whether that fan-out clusters ListenBrainz Labs failures tightly enough to open a
 * provider-keyed breaker.
 *
 * The arm is chosen by the `probe.breaker.arm` system property and read by each `ProviderChain` as
 * it is constructed, so all three arms run the identical harness in one process — under a workload
 * the scheduler orders, a per-arm harness would vary the instrument as well as the key.
 */
private val PROBE_TYPES = setOf(
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.ARTIST_POPULARITY,
    EnrichmentType.ARTIST_TOP_TRACKS,
    EnrichmentType.ARTIST_DISCOGRAPHY,
    EnrichmentType.ARTIST_RADIO_DISCOVERY,
)

private val MAIN_HOST_TYPES = PROBE_TYPES - EnrichmentType.SIMILAR_ARTISTS

private const val LABS_HOST = "labs.api.listenbrainz.org"
private const val MAIN_HOST = "api.listenbrainz.org/1"

private const val TOP_RECORDINGS_BODY =
    """[{"recording_mbid":"11111111-1111-4111-8111-111111111111","recording_name":"T",""" +
        """"artist_name":"A","total_listen_count":10,"total_user_count":3}]"""

private const val RELEASE_GROUPS_BODY =
    """[{"release_group_mbid":"22222222-2222-4222-8222-222222222222",""" +
        """"release_group_name":"RG","artist_name":"A","total_listen_count":10}]"""

private const val ARTIST_POPULARITY_BODY =
    """[{"artist_mbid":"33333333-3333-4333-8333-333333333333",""" +
        """"total_listen_count":10,"total_user_count":3}]"""

private const val RADIO_BODY =
    """{"payload":{"jspf":{"playlist":{"track":[{"title":"T","creator":"A"}]}}}}"""

private fun mbid(i: Int) = "00000000-0000-4000-8000-%012d".format(i)

private fun requestFor(i: Int) = EnrichmentRequest.ForArtist(
    identifiers = EnrichmentIdentifiers(musicBrainzId = mbid(i)),
    name = "Artist$i",
)

/**
 * Which of the two hosts answers first. Latency is the mechanism the question turns on: whether a
 * Labs failure clusters with the next one depends on whether it returns before or after the calls
 * on the healthy host it races.
 */
private enum class Regime(val labsMs: Long, val mainMs: Long) {
    LABS_FAST(0, 25),
    COMPARABLE(10, 10),
    LABS_SLOW(25, 0),
}

/**
 * Which types a caller asks for. The A/B's S1 asks for all five, so four main-host successes
 * surround every Labs failure; a screen showing only similar artists asks for one, and nothing on
 * the healthy host is left to reset the shared counter.
 */
private enum class Mix(val types: Set<EnrichmentType>) {
    ALL_FIVE(PROBE_TYPES),
    SIMILAR_ONLY(setOf(EnrichmentType.SIMILAR_ARTISTS)),
}

private class Stack(regime: Regime, dispatcher: CoroutineDispatcher) {
    val http = FakeHttpClient()

    private val healthy = FakeProvider(
        id = "healthy_other",
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, priority = 10)),
    ).apply {
        givenResult(
            EnrichmentType.GENRE,
            EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Popularity(), id, 1f),
        )
    }

    val engine: EnrichmentEngine

    init {
        http.givenJsonArrayResponse("popularity/top-recordings-for-artist", TOP_RECORDINGS_BODY)
        http.givenJsonArrayResponse("popularity/top-release-groups-for-artist", RELEASE_GROUPS_BODY)
        http.givenJsonArrayResponse("popularity/artist", ARTIST_POPULARITY_BODY)
        http.givenJsonResponse("explore/lb-radio", RADIO_BODY)
        http.givenHttpResultArray(LABS_HOST, HttpResult.ServerError(503))
        http.givenDelay(LABS_HOST, regime.labsMs)
        http.givenDelay(MAIN_HOST, regime.mainMs)
        engine = EnrichmentEngine.Builder()
            .httpClient(http)
            .cache(FakeEnrichmentCache())
            .addProvider(ListenBrainzProvider(http, RateLimiter(0), authToken = "probe-token"))
            .addProvider(healthy)
            .buildOn(dispatcher)
    }

    private val registry get() = (engine as DefaultEnrichmentEngine).probeRegistry

    fun mainHostSkips() = registry.mainHostSkips()

    fun openings() = registry.breakerOpenings().filterKeys { it.startsWith("listenbrainz") }

    fun peaks() = registry.breakerPeaks().filterKeys { it.startsWith("listenbrainz") }

    fun states() = registry.breakerStates()

    fun doomedLabsCalls() = http.requestedUrls.count { LABS_HOST in it }
}

private data class Row(
    val arm: String,
    val regime: Regime,
    val mix: Mix,
    val inFlight: Int,
    val rep: Int,
    val mainApiSkipped: Int,
    val doomedCalls: Int,
    val answersLost: Int,
    val openings: Int,
    val peak: Int,
    val states: String,
    val wallMs: Long,
)

class BreakerConcurrencyProbe {

    /**
     * The harness-can-fail case, hand-computed before it was run: five `enrich()` calls asking only
     * for `SIMILAR_ARTISTS` against a degraded Labs host give the provider-keyed breaker five
     * consecutive failures with nothing to reset it, so it must record exactly one opening, end
     * `OPEN`, and have made exactly five doomed Labs calls and no main-host call. A sixth call
     * asking only for the four main-host types must then be refused outright: four main-host skips
     * and four answers lost.
     *
     * That second half is what makes a zero elsewhere in this file mean something — it shows the
     * instrument can see the ticket's harm when the harm is really there.
     */
    @Test
    fun `harness reproduces a hand-computed clustered case through enrich`() {
        // Given - a Labs-only outage behind the real engine, and no main-host traffic to reset it
        val pool = probePool()
        val stack = Stack(Regime.COMPARABLE, pool)
        // When - five artists are enriched for the Labs-backed type alone, then one for the rest
        val lost = runBlocking(pool) {
            repeat(5) { stack.engine.enrich(requestFor(it + 1), setOf(EnrichmentType.SIMILAR_ARTISTS)) }
            val after = stack.engine.enrich(requestFor(99), MAIN_HOST_TYPES)
            MAIN_HOST_TYPES.count { after.result(it) !is EnrichmentResult.Success }
        }
        val doomed = stack.doomedLabsCalls()
        val mainCalls = stack.http.requestedUrls.count { MAIN_HOST in it }
        val openings = stack.openings().values.sum()
        // Then - one opening, five doomed calls, no main-host call, and four answers refused
        check(doomed == 5) { "doomed Labs calls: $doomed, expected 5" }
        check(mainCalls == 0) { "main-host calls: $mainCalls, expected 0" }
        check(openings == 1) { "breaker openings: $openings, expected 1" }
        check(stack.mainHostSkips() == 4) { "main-host skips: ${stack.mainHostSkips()}, expected 4" }
        check(lost == 4) { "answers lost: $lost, expected 4" }
        println("[hand-computed] doomed=$doomed main=$mainCalls openings=$openings states=${stack.states()}")
    }

    /**
     * The mechanism the sweep's result rests on, asserted rather than inferred: one
     * `ListenBrainzProvider` holds one [RateLimiter], and `RateLimiter.execute` holds its mutex
     * across the call, so both hosts' requests are issued strictly one at a time however wide the
     * fan-out above them is. Latency cannot reorder what a mutex has already serialised.
     */
    @Test
    fun `one provider's calls are serialised however wide the fan-out`() {
        // Given - a stack whose Labs host answers instantly and whose main host is slow
        val pool = probePool()
        val stack = Stack(Regime.LABS_FAST, pool)
        // When - twenty artists are enriched concurrently for all five types
        val wall = runBlocking(pool) {
            val start = System.nanoTime()
            coroutineScope {
                (1..20).map { async { stack.engine.enrich(requestFor(it), PROBE_TYPES) } }.awaitAll()
            }
            (System.nanoTime() - start) / 1_000_000
        }
        // Then - the run costs the sum of the eighty slow calls, not the longest of twenty in parallel
        val serialisedFloorMs = 20 * 4 * Regime.LABS_FAST.mainMs
        check(wall >= serialisedFloorMs) {
            "wall $wall ms is below the serialised floor $serialisedFloorMs ms, so the limiter did " +
                "not serialise and the sweep's ordering argument does not hold"
        }
        println("[serialisation] wall=${wall}ms floor=${serialisedFloorMs}ms")
    }

    @Test
    fun `runs the concurrency sweep`() {
        // Given - the A/B's S1 workload through enrich(), swept over arm, regime, mix and fan-out
        val arms = listOf("control", "host", "type")
        val inFlights = listOf(1, 4, 20)
        val reps = 20
        val artists = 20
        val rows = mutableListOf<Row>()
        // When - every cell is run to completion on a pool this probe owns
        for (arm in arms) {
            System.setProperty("probe.breaker.arm", arm)
            for (regime in Regime.values()) {
                for (mix in Mix.values()) {
                    for (inFlight in inFlights) {
                        repeat(reps) { rep ->
                            rows += runCell(arm, regime, mix, inFlight, rep, artists)
                        }
                    }
                }
            }
        }
        System.clearProperty("probe.breaker.arm")
        // Then - the raw per-repetition table lands under probe-results/
        val out = File("../probe-results/concurrency.psv")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine(
                    "arm|regime|mix|in_flight|rep|main_api_skipped|doomed_calls|answers_lost|" +
                        "openings|peak_consecutive_failures|states|wall_ms",
                )
                for (r in rows) {
                    appendLine(
                        listOf(
                            r.arm, r.regime, r.mix, r.inFlight, r.rep, r.mainApiSkipped,
                            r.doomedCalls, r.answersLost, r.openings, r.peak, r.states, r.wallMs,
                        ).joinToString("|"),
                    )
                }
            },
        )
        println(summarise(rows))
        File("../probe-results/concurrency-summary.psv").writeText(summarise(rows))
    }

    private fun runCell(
        arm: String,
        regime: Regime,
        mix: Mix,
        inFlight: Int,
        rep: Int,
        artists: Int,
    ): Row {
        val pool = probePool()
        val stack = Stack(regime, pool)
        var lost = 0
        val start = System.nanoTime()
        runBlocking(pool) {
            for (batch in (1..artists).chunked(inFlight)) {
                coroutineScope {
                    batch.map { artist ->
                        async { stack.engine.enrich(requestFor(artist), mix.types) }
                    }.awaitAll()
                }.forEach { results ->
                    lost += MAIN_HOST_TYPES.count {
                        it in mix.types && results.result(it) !is EnrichmentResult.Success
                    }
                }
            }
        }
        val wall = (System.nanoTime() - start) / 1_000_000
        stack.engine.close()
        return Row(
            arm = arm,
            regime = regime,
            mix = mix,
            inFlight = inFlight,
            rep = rep,
            mainApiSkipped = stack.mainHostSkips(),
            doomedCalls = stack.doomedLabsCalls(),
            answersLost = lost,
            openings = stack.openings().values.sum(),
            peak = stack.peaks().values.maxOrNull() ?: 0,
            states = stack.states().toString(),
            wallMs = wall,
        )
    }

    /**
     * The pool this probe's fan-out runs on, one per cell. `enrich()` spends its budget and every
     * `delay` beneath it against the clock of whatever dispatcher it was handed, so a cell sharing
     * `Dispatchers.Default` with the rest of the suite would be measuring the suite. Sixteen
     * threads, so twenty concurrent enrichments are limited by the scripted latencies rather than
     * by the pool. Daemon threads: a cell needs no teardown.
     */
    private fun probePool(): CoroutineDispatcher =
        Executors.newFixedThreadPool(16) { runnable ->
            Thread(runnable, "probe-fanout").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun summarise(rows: List<Row>): String = buildString {
        appendLine(
            "arm|regime|mix|in_flight|reps|reps_with_an_opening|openings_min|openings_max|" +
                "peak_min|peak_max|skipped_min|skipped_max|lost_min|lost_max|doomed_min|doomed_max",
        )
        rows.groupBy { listOf(it.arm, it.regime.name, it.mix.name, "%02d".format(it.inFlight)) }
            .entries
            .sortedBy { it.key.joinToString("|") }
            .forEach { (key, cells) ->
                appendLine(
                    (
                        key + listOf(
                            cells.size,
                            cells.count { it.openings > 0 },
                            cells.minOf { it.openings }, cells.maxOf { it.openings },
                            cells.minOf { it.peak }, cells.maxOf { it.peak },
                            cells.minOf { it.mainApiSkipped }, cells.maxOf { it.mainApiSkipped },
                            cells.minOf { it.answersLost }, cells.maxOf { it.answersLost },
                            cells.minOf { it.doomedCalls }, cells.maxOf { it.doomedCalls },
                        )
                        ).joinToString("|"),
                )
            }
    }
}
