package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kit's worked example for [UpstreamPools], [TestStack], [EntityIdentity] and
 * [assertNoUrlRequestedTwice]: the real default provider stack, offline, over one scenario.
 *
 * **One scenario, deliberately.** The full set belongs to the ticket that owns the composed-stack
 * harness; this proves the four primitives compose end to end and stops there.
 *
 * The scenario is the LRCLIB first-result defect. A request for `Song` by `David Bowie` finds
 * `Alabama Song` at index 0 of the search pool — same artist, wrong track. The right answer for
 * every LRCLIB type is a decline, and the wrong one is a confident answer about a different song.
 */
class ComposedStackWorkedExampleTest {

    @Test
    fun `a wrong-title search hit is declined rather than answered`() = runTest {
        // Given - the real stack, offline, over a pool whose first LRCLIB hit is the wrong track
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")

        // When - enriching for the three types LRCLIB is capable of
        val results = engine.enrich(request, LRCLIB_TYPES)

        // Then - each declines; a Success here would be an answer about Alabama Song
        for (type in LRCLIB_TYPES) {
            val result = results.raw[type]
            assertTrue(
                "$type should decline a wrong-title hit, but was $result",
                result !is EnrichmentResult.Success,
            )
        }
    }

    @Test
    fun `the identity rule holds over the composed stack`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")

        // When - enriching for the LRCLIB types and one type a keyed provider answers
        val results = engine.enrich(request, LRCLIB_TYPES + EnrichmentType.TRACK_POPULARITY)

        // Then - the identity rule holds in its name-search form
        // The weak form is the correct one here and the strong one would refuse to run: this
        // request carries no identifier, so it resolves by name search, which never sets canonical
        // names. See EntityIdentity's KDoc for what that costs and what still gets asserted.
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
    }

    @Test
    fun `no upstream URL is requested twice in one enrich`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")

        // When - enriching for the LRCLIB types and one type a keyed provider answers
        engine.enrich(request, LRCLIB_TYPES + EnrichmentType.TRACK_POPULARITY)

        // Then - every upstream URL was fetched at most once
        http.assertNoUrlRequestedTwice()
    }

    @Test
    fun `every Success carries a provenance`() = runTest {
        // Given - a pool whose LRCLIB hit is the requested track, so LYRICS_SYNCED answers instead
        // of declining — a scenario whose only Success comes out of a merger cannot tell this rule
        // from a constant, since PopularityMerger's own `?: FUZZY_NAME` fallback never leaves a
        // merged result's provenance null regardless of whether a contributor was stamped
        val http = UpstreamPools.load(QUALIFIER_MATCH_SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(QUALIFIER_MATCH_TITLE, QUALIFIER_MATCH_ARTIST)

        // When - enriching for the type LRCLIB answers directly, through the engine's non-merged
        // chain path
        val results = engine.enrich(request, setOf(EnrichmentType.LYRICS_SYNCED))

        // Then - the hit is a genuine Success, and no Success reaches a consumer without saying how
        // its entity was selected
        val lyricsResult = results.raw[EnrichmentType.LYRICS_SYNCED]
        assertTrue(
            "expected a Success from LRCLIB's exact hit, got $lyricsResult",
            lyricsResult is EnrichmentResult.Success,
        )
        val unstamped = results.raw.values
            .filterIsInstance<EnrichmentResult.Success>()
            .filter { it.provenance == null }
        assertTrue("Success results with no provenance: ${unstamped.map { it.type }}", unstamped.isEmpty())
    }

    private companion object {
        const val SCENARIO = "lrclib-first-result"
        const val QUALIFIER_MATCH_SCENARIO = "lrclib-qualifier-match"
        const val QUALIFIER_MATCH_TITLE = "Starman - 2012 Remaster"
        const val QUALIFIER_MATCH_ARTIST = "David Bowie"
        val LRCLIB_TYPES = setOf(
            EnrichmentType.LYRICS_SYNCED,
            EnrichmentType.LYRICS_PLAIN,
            EnrichmentType.TRACK_METADATA,
        )
    }
}
