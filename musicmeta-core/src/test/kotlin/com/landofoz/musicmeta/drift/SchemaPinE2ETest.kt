package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.e2e.E2ETestFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The daily provider drift watch: one request per pinned upstream route, asserting that the fields
 * the mappers read are still where they were.
 *
 * This is what the scheduled job runs. It replaces running the whole e2e suite against live APIs,
 * which was ~380 requests and therefore arithmetically certain to go red on shedding alone — a
 * daily red is a rota for ignoring the email, which is the failure this watch exists to avoid.
 *
 * What it does not cover, stated plainly: ranking, confidence, mergers and identity resolution
 * against real data. Those are the rest of `e2e/`, which is now `workflow_dispatch` only. The pin
 * answers "did a field move", not "does the pipeline still work".
 *
 * It lives outside the `e2e` package deliberately. The suite is selected as
 * `com.landofoz.musicmeta.e2e.*`, so a pin in that package is swept into every suite run, and the
 * suite's `--rerun-tasks` overwrites the report the watch is read from.
 *
 * Run manually: ./gradlew :musicmeta-core:schemaPin -Dinclude.e2e=true
 */
class SchemaPinE2ETest {

    @Test
    fun `every pinned upstream route still carries the fields its mapper reads`() = runBlocking {
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")

        // Given - every pinned route, with the three credentialed providers' keys resolved
        val targets = allSchemaPinTargets(
            lastFmApiKey = E2ETestFixture.prop("lastfm.apikey"),
            fanartTvApiKey = E2ETestFixture.prop("fanarttv.apikey"),
            discogsToken = E2ETestFixture.prop("discogs.token"),
        )

        // When - each route is requested once and its answer classified
        val results = targets.map { target ->
            // One request at a time with a MusicBrainz-safe gap between them. The whole run is
            // thirteen requests, so the wall-clock cost of the slowest provider's limit is
            // seconds — cheaper than a per-provider limiter that has to be right.
            delay(REQUEST_GAP_MS)
            PinResult(target, probe(E2ETestFixture.httpClient, target))
        }

        reportLines(results).forEach { println("schema-pin $it") }
        unavailableCounts(results).toSortedMap().forEach { (kind, count) ->
            println("::notice::schema-pin unavailable: $kind x$count")
        }

        // Then - no route drifted, and the run was not blind
        val findings = runFindings(results)
        findings.forEach { println(it) }
        if (findings.isNotEmpty()) {
            fail(findings.joinToString("\n"))
        }
    }

    private companion object {
        /** MusicBrainz allows one request a second and is the tightest published limit here. */
        const val REQUEST_GAP_MS = 1_100L
    }
}
