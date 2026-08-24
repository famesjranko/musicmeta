package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.UpstreamPools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Songwriter credits live on the MusicBrainz *work*, not the recording, and `inc=work-rels` returns
 * that work as a stub — id and title, no `relations`. Asking for `work-level-rels` as well inlines
 * the work's own relations into the same response, at no extra request.
 *
 * The `musicbrainz-work-credits` pool answers only a URL containing `work-level-rels`, so a lookup
 * that does not ask for it matches no stub and reaches this test as an absent recording. That is
 * deliberate: the defect was in the request, not the parse, and a test that asserted only on parsed
 * output would have gone green against a hand-written fixture — which is exactly how this shipped
 * broken (`MusicBrainzParserTest.RECORDING_WITH_WORK_REL`, and `scenario.md` in the pool).
 */
class MusicBrainzWorkCreditsTest {

    @Test fun `a recording lookup carries the work's writers, without a second request`() = runTest {
        // Given - the live-captured recording response, served only to a URL asking for work-level-rels
        val http = UpstreamPools.load(SCENARIO)
        val api = MusicBrainzApi(http, RateLimiter(0))

        // When - looking the recording up and parsing its credits
        val lookup = api.lookupRecording(RECORDING_MBID)
        val json = (lookup as? MusicBrainzLookup.Found)?.value
        assertTrue(
            "the recording lookup matched no stub, so it did not ask for work-level-rels: $lookup",
            json != null,
        )
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json!!)

        // Then - every writer on the work is credited
        val writers = credits.filter { it.role == "writer" }.map { it.name }.toSet()
        assertTrue(
            "expected the work's five writers, got ${credits.map { "${it.role}:${it.name}" }}",
            writers.containsAll(EXPECTED_WRITERS),
        )

        // Then - exactly one upstream request was made, which is the whole point of work-level-rels
        assertTrue(
            "expected one recording request, got ${http.requestedUrls}",
            http.requestedUrls.count { it.contains("/recording/") } == 1,
        )
        assertTrue(
            "a separate /work/ lookup defeats the fix, got ${http.requestedUrls}",
            http.requestedUrls.none { it.contains("/work/") },
        )
    }

    private companion object {
        const val SCENARIO = "musicbrainz-work-credits"
        const val RECORDING_MBID = "cab6f522-4fdf-41bb-b3af-5b93b899d062"

        /** "Karma Police" is credited to all five members of Radiohead on the work. */
        val EXPECTED_WRITERS = setOf(
            "Colin Greenwood",
            "Ed O’Brien",
            "Jonny Greenwood",
            "Philip Selway",
            "Thom Yorke",
        )
    }
}
