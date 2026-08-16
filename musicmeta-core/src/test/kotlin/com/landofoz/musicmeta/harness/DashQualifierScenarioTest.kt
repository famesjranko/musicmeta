package com.landofoz.musicmeta.harness

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.EntityIdentity
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composed stack over the `dash-qualifier` pool: a track request spelling its reissue
 * qualifier with a dash (`Starman - 2012 Remaster`) reaches MusicBrainz's
 * `MusicBrainzQualifierFallback.qualifierFallbackCandidates` dash-form step, which the original
 * #210 fix added. The pool's stripped-title recording search ties a studio recording against a
 * live one at the same score, so a real answer here also proves
 * `MusicBrainzEnricher.pickBestRecording`'s blank-disambiguation tie-break — the studio/live
 * distinction — actually runs over the composed stack, not only in a single-provider test.
 */
class DashQualifierScenarioTest {

    @Test
    fun `the dash-form fallback resolves, and picks the studio recording over the tied live one`() = runTest {
        // Given - the real stack, offline, over a pool where only the dash-stripped title search
        // is stubbed, tying a studio recording against a live one at the same score
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for the type MusicBrainz answers directly
        val results = engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - resolved via the dash-form fallback, to the studio recording specifically —
        // a live take at the same score must not win
        val result = results.raw[EnrichmentType.TRACK_METADATA]
        assertTrue("expected a Success via the dash-form fallback, got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("musicbrainz", success.provider)
        assertEquals(
            "the studio/live tie-break should prefer the blank-disambiguation candidate",
            STUDIO_MBID,
            success.resolvedIdentifiers?.musicBrainzId,
        )
    }

    @Test
    fun `the identity rule holds over the composed stack`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for the type MusicBrainz answers directly
        val results = engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - the identity rule holds in its name-search form: this request carries no
        // identifier, so it resolves by name search, which never sets canonical names
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
    }

    @Test
    fun `every Success carries a provenance`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for the type MusicBrainz answers directly
        val results = engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - the resolved Success exists and carries a provenance — a non-empty guard first,
        // so this cannot pass by finding no Success to check at all
        val successes = results.raw.values.filterIsInstance<EnrichmentResult.Success>()
        assertTrue("expected at least one Success to exercise the provenance rider", successes.isNotEmpty())
        val unstamped = successes.filter { it.provenance == null }
        assertTrue("Success results with no provenance: ${unstamped.map { it.type }}", unstamped.isEmpty())
    }

    @Test
    fun `no upstream URL is requested twice in one enrich`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for the type MusicBrainz answers directly
        engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - every upstream URL was fetched at most once
        http.assertNoUrlRequestedTwice()
    }

    private companion object {
        const val SCENARIO = "dash-qualifier"
        const val TITLE = "Starman - 2012 Remaster"
        const val ARTIST = "David Bowie"
        const val STUDIO_MBID = "rec-studio"
    }
}
