package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Which MusicBrainz responses can answer "does this release have a front cover", checked against the
 * live API rather than against a fixture.
 *
 * `MusicBrainzRelease.hasFrontCover` is null where the response carried no `cover-art-archive`
 * object, and the whole of that distinction rests on one upstream fact: a `/release?query=` search
 * omits the object and a `/release/{mbid}` lookup carries it. Nothing in this repo can verify that,
 * because a hand-written fixture says whatever it was written to say — three of them asserted a
 * search response carrying the object, which MusicBrainz never sends, and that is what hid the flag
 * reading `false` for every search hit. This test is the thing that would have caught them.
 *
 * It is not coverage for the flag's parsing: `MusicBrainzParserTest` pins that, gates a merge, and
 * stays the evidence. Under `-Dinclude.e2e=true` only, so a MusicBrainz outage never fails a build.
 *
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*ReleaseCoverArtFlag*"
 */
class ReleaseCoverArtFlagE2ETest {

    private val api = MusicBrainzApi(E2ETestFixture.httpClient, E2ETestFixture.mbRateLimiter)

    @Test
    fun `a release search leaves every hit's front cover unknown`() = runBlocking {
        // Given - e2e enabled, and an album MusicBrainz holds many editions of
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")

        // When - searching releases by name, the one call searchAlbumCandidates makes
        val releases = api.searchReleases("Abbey Road", "The Beatles", SEARCH_LIMIT)

        // Then - the search answered, and not one hit was in a position to state anything about art
        assertTrue("an empty pool is an unanswered query, not a pass", releases.isNotEmpty())
        val stated = releases.filter { it.hasFrontCover != null }
        assertEquals("a search hit carried a cover-art-archive object", emptyList<String>(), stated.map { it.id })
    }

    @Test
    fun `a release lookup carries the object a search omits`() = runBlocking {
        // Given - e2e enabled, and one release identifier MusicBrainz resolves
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")

        // When - looking that identifier up, the call the enricher makes for a known release
        val lookup = api.lookupRelease(LOOKUP_RELEASE_MBID)

        // Then - the flag holds an answer rather than the null a search leaves behind
        val found = lookup as? MusicBrainzLookup.Found
        assertNotNull("expected a release, got $lookup", found)
        assertNotNull("a release lookup carried no cover-art-archive object", found!!.value.hasFrontCover)
    }

    private companion object {
        /**
         * Abbey Road's 2009 stereo remaster, taken from a live release search on 2026-08-22.
         *
         * Safe because only the object's *presence* is asserted, never its `front` value: whether
         * this release has art is Cover Art Archive data anyone can add or remove, and were the id
         * ever merged away the redirect lands on a release carrying the object just the same.
         */
        const val LOOKUP_RELEASE_MBID = "c93e1d4c-89fa-3139-9c65-8542a39f07d1"

        /** Small on purpose — the claim is about every hit's shape, which one page already shows. */
        const val SEARCH_LIMIT = 5
    }
}
