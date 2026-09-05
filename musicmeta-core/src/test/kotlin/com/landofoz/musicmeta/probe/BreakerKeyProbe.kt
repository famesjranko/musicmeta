package com.landofoz.musicmeta.probe

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * The A/B harness for `.scratch/tech-debt/issues/06`. Identical on all three arms; only
 * `ProviderChain.breakerKeyFor` differs. Writes its raw table to `probe-results/`.
 */
private const val ARM = "control"

private const val LABS_HOST = "labs.api.listenbrainz.org"
private const val MAIN_HOST = "api.listenbrainz.org/1"

/** The five ListenBrainz types the plan names. SIMILAR_ARTISTS rides Labs; the other four the main host. */
private val PROBE_TYPES = listOf(
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.ARTIST_POPULARITY,
    EnrichmentType.ARTIST_TOP_TRACKS,
    EnrichmentType.ARTIST_DISCOGRAPHY,
    EnrichmentType.ARTIST_RADIO_DISCOVERY,
)

private val MAIN_HOST_TYPES = PROBE_TYPES - EnrichmentType.SIMILAR_ARTISTS

private fun mbid(i: Int) = "00000000-0000-4000-8000-%012d".format(i)

private fun requestFor(i: Int) = EnrichmentRequest.ForArtist(
    identifiers = EnrichmentIdentifiers(musicBrainzId = mbid(i)),
    name = "Artist$i",
)

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

private enum class Scenario { S1_LABS_DOWN, S2_PROVIDER_DOWN }

private enum class Ordering { INTERLEAVE, BURST }

private data class Metrics(
    val mainApiCallsSkipped: Int,
    val doomedCallsMade: Int,
    val answersLost: Int,
    val breakerStates: Map<String, String>,
    val wallClockMs: Long,
    val successes: Int,
    val walks: Int,
)

private class Harness(private val scenario: Scenario) {
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

    private val listenBrainz: EnrichmentProvider =
        ListenBrainzProvider(http, RateLimiter(0), authToken = "probe-token")

    val registry = ProviderRegistry(listOf(listenBrainz, healthy))

    init {
        // Healthy main host, always stubbed; S2 overrides it with a 5xx below.
        http.givenJsonArrayResponse("popularity/top-recordings-for-artist", TOP_RECORDINGS_BODY)
        http.givenJsonArrayResponse("popularity/top-release-groups-for-artist", RELEASE_GROUPS_BODY)
        http.givenJsonArrayResponse("popularity/artist", ARTIST_POPULARITY_BODY)
        http.givenJsonResponse("explore/lb-radio", RADIO_BODY)
        // Labs is 5xx in both scenarios.
        http.givenHttpResultArray(LABS_HOST, com.landofoz.musicmeta.http.HttpResult.ServerError(503))
        if (scenario == Scenario.S2_PROVIDER_DOWN) {
            http.givenHttpResultArray(MAIN_HOST, com.landofoz.musicmeta.http.HttpResult.ServerError(503))
            http.givenHttpResult(MAIN_HOST, com.landofoz.musicmeta.http.HttpResult.ServerError(503))
        }
    }

    fun run(artists: Int, ordering: Ordering): Metrics {
        var skippedMain = 0
        var answersLost = 0
        var successes = 0
        var walks = 0
        val start = System.nanoTime()
        val pairs = when (ordering) {
            Ordering.INTERLEAVE -> (1..artists).flatMap { a -> PROBE_TYPES.map { a to it } }
            Ordering.BURST -> PROBE_TYPES.flatMap { t -> (1..artists).map { it to t } }
        }
        runBlocking {
            for ((artist, type) in pairs) {
                val chain = registry.chainFor(type) ?: error("no chain for $type")
                val (result, execution) = chain.resolveWithExecution(requestFor(artist))
                walks++
                val skipped = "listenbrainz" in execution.skippedForOpenBreaker
                val healthyHost = scenario == Scenario.S1_LABS_DOWN && type in MAIN_HOST_TYPES
                if (skipped && type in MAIN_HOST_TYPES) skippedMain++
                if (healthyHost && result !is EnrichmentResult.Success) answersLost++
                if (result is EnrichmentResult.Success) successes++
            }
            // One healthy-provider walk per artist, to show no cross-provider contamination.
            for (artist in 1..artists) {
                registry.chainFor(EnrichmentType.GENRE)!!.resolveWithExecution(requestFor(artist))
            }
        }
        val wall = (System.nanoTime() - start) / 1_000_000
        val failingHost = if (scenario == Scenario.S1_LABS_DOWN) LABS_HOST else "listenbrainz.org"
        val doomed = http.requestedUrls.count { failingHost in it }
        return Metrics(
            mainApiCallsSkipped = skippedMain,
            doomedCallsMade = doomed,
            answersLost = answersLost,
            breakerStates = registry.breakerStates().mapValues { it.value.name },
            wallClockMs = wall,
            successes = successes,
            walks = walks,
        )
    }
}

class BreakerKeyProbe {

    /**
     * The harness-can-fail case, hand-computed: five scripted Labs failures and nothing else must
     * leave exactly one breaker OPEN and every other key CLOSED, with five doomed calls and no
     * skips. Mutate any of the four stated numbers and this goes red.
     */
    @Test
    fun `harness reproduces a hand-computed five-failure case`() {
        // Given - a Labs-only outage and five SIMILAR_ARTISTS walks, one per artist
        val harness = Harness(Scenario.S1_LABS_DOWN)
        // When - only the Labs-backed type is walked, five times
        val states = runBlocking {
            for (a in 1..5) {
                harness.registry.chainFor(EnrichmentType.SIMILAR_ARTISTS)!!
                    .resolveWithExecution(requestFor(a))
            }
            harness.registry.breakerStates().mapValues { it.value.name }
        }
        val doomed = harness.http.requestedUrls.count { LABS_HOST in it }
        val mainCalls = harness.http.requestedUrls.count { MAIN_HOST in it }
        // Then - five doomed Labs calls, zero main-host calls, and exactly one OPEN breaker
        val expectedDoomed = 5
        val expectedMainCalls = 0
        val expectedOpen = 1
        check(doomed == expectedDoomed) { "doomed Labs calls: $doomed, expected $expectedDoomed" }
        check(mainCalls == expectedMainCalls) { "main-host calls: $mainCalls, expected $expectedMainCalls" }
        val open = states.filterValues { it == "OPEN" }
        check(open.size == expectedOpen) { "OPEN breakers: $open, expected exactly $expectedOpen" }
        println("[$ARM] hand-computed case: doomed=$doomed main=$mainCalls states=$states")
    }

    /**
     * Control-behaviour sanity: five consecutive failures open a breaker, and an interleaved
     * success resets the counter so the sixth failure does not.
     */
    @Test
    fun `interleaved success resets the failure counter`() {
        // Given - a fresh breaker at the shipped threshold
        val breaker = com.landofoz.musicmeta.http.CircuitBreaker()
        // When - four failures, a success, then four more
        repeat(4) { breaker.recordFailure() }
        val afterFour = breaker.state.name
        breaker.recordSuccess()
        repeat(4) { breaker.recordFailure() }
        val afterReset = breaker.state.name
        breaker.recordFailure()
        val afterFifth = breaker.state.name
        // Then - only the fifth consecutive failure opens it
        check(afterFour == "CLOSED") { "after four: $afterFour" }
        check(afterReset == "CLOSED") { "after reset then four: $afterReset" }
        check(afterFifth == "OPEN") { "after fifth: $afterFifth" }
    }

    @Test
    fun `runs the pre-registered probe`() {
        // Given - the frozen two scenarios at both sizes, plus the burst-ordering sensitivity run
        val rows = mutableListOf<String>()
        rows += "arm|scenario|ordering|artists|walks|main_api_calls_skipped|doomed_calls_made|" +
            "answers_lost|successes|wall_clock_ms|breaker_states"
        // When - every cell is run
        for (scenario in Scenario.values()) {
            for (ordering in Ordering.values()) {
                for (artists in listOf(1, 20)) {
                    val m = Harness(scenario).run(artists, ordering)
                    rows += listOf(
                        ARM, scenario.name, ordering.name, artists, m.walks,
                        m.mainApiCallsSkipped, m.doomedCallsMade, m.answersLost, m.successes,
                        m.wallClockMs, m.breakerStates.toString(),
                    ).joinToString("|")
                }
            }
        }
        // Then - the raw table lands on this arm's branch
        val out = File("../probe-results/$ARM.psv")
        out.parentFile.mkdirs()
        out.writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
