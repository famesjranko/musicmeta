package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The enrich-timeout-drops-provenance regression: an enrich that times out mid fan-out can hand a
 * consumer a `Success` whose `provenance` is null, contrary to `EnrichmentResult.kt`'s promise that
 * `null` means only "built outside the engine". `COUNTRY` comes from identity resolution's
 * write-through (`DefaultEnrichmentEngine.kt`'s `resolveIdentity`), which runs — and can write a
 * null-provenance `Success` into `results` — inside the same `withTimeoutOrNull` block whose *end*
 * runs `stampProvenance`, the only thing that fills that null in. A deadline that expires before
 * `stampProvenance` runs leaves the write-through entry exactly as it was.
 *
 * **Built via [EnrichmentEngine.Builder] directly, not [TestStack.build]**: the latter does not
 * expose `enrichTimeoutMs`. Call order still follows [TestStack]'s KDoc — `httpClient` and
 * `apiKeys` precede `withDefaultProviders()`.
 *
 * **The scenario, and why it reproduces deterministically rather than racily.** The pool's search
 * hit already carries a `wikidata` relation, so identity resolution's own MusicBrainz call
 * (`enrichArtist`'s `needsRelations` check) is satisfied by the search alone — one undelayed call
 * that both answers `COUNTRY` and leaves `BAND_MEMBERS` unresolved. `BAND_MEMBERS` needs the full
 * artist lookup, memoized under a different key from the search, so it costs a second MusicBrainz
 * call — and `RateLimiter` (`EnrichmentEngine.kt`'s 1100ms MusicBrainz instance) makes that second
 * call wait. Under `runTest` the limiter's `delay` runs on the same virtual clock `withTimeoutOrNull`
 * measures against, so at a `enrichTimeoutMs` below 1100ms the deadline expiring mid-wait is not a
 * race the scheduler happens to win — it is the only outcome the virtual clock can produce, every
 * run, because nothing in this path performs real I/O ([UpstreamPools] loads a [FakeHttpClient]).
 *
 * **The `BAND_MEMBERS` timeout assertion is load-bearing, not decoration.** The reproduction depends
 * on the MusicBrainz rate-limiter's interval sitting *above* `TIGHT_TIMEOUT_MS`. If that constant
 * ever drops to or below the timeout, the second call stops being delayed, the run completes inside
 * the deadline, `stampProvenance` runs, and the `COUNTRY` provenance assertion below would pass for
 * a reason that has nothing to do with this defect — asserting nothing while looking green. Asserting
 * that `BAND_MEMBERS` actually came back `Error(TIMEOUT)` is what makes a constant change fail this
 * test loudly instead of leaving it silently vacuous.
 *
 * See this file's `control - …` test below for the pairing `docs/pitfalls.md` §17 requires:
 * identical fixtures and request, differing only in `enrichTimeoutMs`, showing the null is caused by
 * the truncation and not by the fixtures or wiring.
 */
class EnrichTimeoutProvenanceRegressionTest {

    @Ignore(
        "Red now, and owned by the enrich-timeout-drops-provenance defect: an enrich that times out " +
            "mid fan-out can return an identity-write-through Success whose provenance is null, " +
            "against the published guarantee that every engine-produced Success sets it. Remove this " +
            "mark once a truncated run either stamps provenance like a completed run would, or the " +
            "guarantee is narrowed to say a truncated run's Success may carry none — the test must go " +
            "red first if it does not.",
    )
    @Test
    fun `identity write-through Success keeps its provenance even when the run times out`() = runTest {
        // Given - the real stack, offline, over a scenario whose identity resolution answers from a
        // search hit alone while BAND_MEMBERS still needs a second, rate-limited MusicBrainz call
        val http = UpstreamPools.load(SCENARIO)
        val engine = EnrichmentEngine.Builder()
            .httpClient(http)
            .apiKeys(TestStack.ALL_KEYS)
            .config(EnrichmentConfig(enrichTimeoutMs = TIGHT_TIMEOUT_MS))
            .withDefaultProviders()
            .build()
        val request = EnrichmentRequest.forArtist(ARTIST)

        // When - enriching for the identity-write-through type and the type whose chain pays the
        // rate limiter's interval, which under runTest's virtual clock lands past the tight deadline
        val results = engine.enrich(request, setOf(EnrichmentType.COUNTRY, EnrichmentType.BAND_MEMBERS))

        // Then - BAND_MEMBERS timing out is the proof the run actually truncated, and COUNTRY is
        // still owed a provenance by EnrichmentResult.kt's promise regardless of that truncation
        val bandMembers = results.raw[EnrichmentType.BAND_MEMBERS]
        assertTrue(
            "expected BAND_MEMBERS to time out, which is what proves this run truncated; got $bandMembers",
            bandMembers is EnrichmentResult.Error && bandMembers.errorKind == ErrorKind.TIMEOUT,
        )
        val country = results.raw[EnrichmentType.COUNTRY]
        assertTrue("expected a Success for COUNTRY, got $country", country is EnrichmentResult.Success)
        assertNotNull(
            "EnrichmentResult.kt promises provenance is null only for a result built outside the " +
                "engine — this one is engine-produced",
            (country as EnrichmentResult.Success).provenance,
        )
    }

    @Test
    fun `control - the same fixtures and request complete and keep provenance at a generous timeout`() =
        runTest {
            // Given - the identical scenario and request as the ignored test above, differing only
            // in enrichTimeoutMs
            val http = UpstreamPools.load(SCENARIO)
            val engine = EnrichmentEngine.Builder()
                .httpClient(http)
                .apiKeys(TestStack.ALL_KEYS)
                .config(EnrichmentConfig(enrichTimeoutMs = GENEROUS_TIMEOUT_MS))
                .withDefaultProviders()
                .build()
            val request = EnrichmentRequest.forArtist(ARTIST)

            // When - enriching for the same two types with room for both MusicBrainz calls to land
            val results = engine.enrich(request, setOf(EnrichmentType.COUNTRY, EnrichmentType.BAND_MEMBERS))

            // Then - both calls complete, so BAND_MEMBERS is a genuine Success rather than a
            // timeout, and COUNTRY keeps its provenance — isolating the ignored test's null to the
            // truncation itself, not to a fixture or wiring defect
            val bandMembers = results.raw[EnrichmentType.BAND_MEMBERS]
            assertTrue(
                "expected BAND_MEMBERS to complete at a generous timeout, got $bandMembers",
                bandMembers is EnrichmentResult.Success,
            )
            val country = results.raw[EnrichmentType.COUNTRY]
            assertTrue("expected a Success for COUNTRY, got $country", country is EnrichmentResult.Success)
            assertNotNull(
                "control run: provenance must be non-null here, or the ignored test above proves nothing",
                (country as EnrichmentResult.Success).provenance,
            )
        }

    companion object {
        private const val SCENARIO = "musicbrainz-artist-wikidata-and-members"
        private const val ARTIST = "Radiohead"

        /** Below the MusicBrainz RateLimiter's 1100ms interval, so the second call outlives it. */
        private const val TIGHT_TIMEOUT_MS = 500L

        /** Well above both MusicBrainz calls' combined cost, so the run completes. */
        private const val GENEROUS_TIMEOUT_MS = 5_000L
    }
}
