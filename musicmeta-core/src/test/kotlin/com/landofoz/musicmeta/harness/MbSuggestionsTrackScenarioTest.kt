package com.landofoz.musicmeta.harness

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.EntityIdentity
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composed stack over the `mb-suggestions-track` pool: MusicBrainz's own canonical search
 * misses and offers near-miss suggestions. The #210 defect this reproduces was a global veto that
 * skipped every other provider's fan-out and stamped a manufactured `NotFound(provider = "engine",
 * suggestions = ...)` onto every requested type instead. Post-fix, the suggestions live once at the
 * top level, and every other eligible provider still runs its own search rather than being skipped —
 * observable two ways: the provider's own search URL was actually requested, and a chain that ran
 * and found nothing reports the ordinary [ProviderChain]-owned `"all_providers"` id
 * (`ProviderChain.kt:268`), never the short-circuit's `"engine"` one.
 */
class MbSuggestionsTrackScenarioTest {

    @Test
    fun `MusicBrainz offers suggestions once at the top level, and LRCLIB still answers on its own`() = runTest {
        // Given - the real stack, offline, over a pool where MusicBrainz's canonical search comes
        // up empty and its miss-suggestion search offers two near-miss recordings, and LRCLIB has
        // no stub at all
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for MusicBrainz's own probe type and one LRCLIB-exclusive type
        val results = engine.enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED))

        // Then - the top-level identity carries the suggestions, from a genuine AMBIGUOUS canonical
        // resolution — a non-empty guard on the suggestion list itself, so this cannot pass on an
        // empty enumeration
        assertEquals(CanonicalStatus.AMBIGUOUS, results.identity.status)
        assertTrue("expected non-empty top-level suggestions", results.identity.suggestions.isNotEmpty())

        // And - LRCLIB's own search endpoint was actually requested, proving its chain ran rather
        // than being short-circuited by the identity suggestions
        assertTrue(
            "expected LRCLIB's /api/search endpoint to have been requested, urls=${http.requestedUrls}",
            http.requestedUrls.any { it.contains("/api/search") },
        )

        // And - the type's own decline is the chain's ordinary exhausted-fan-out id, never the
        // pre-fix short-circuit's manufactured "engine" id, and carries no suggestions of its own
        val lyrics = results.raw[EnrichmentType.LYRICS_SYNCED]
        assertTrue("expected LRCLIB's chain to have run and declined, got $lyrics", lyrics is EnrichmentResult.NotFound)
        val lyricsNotFound = lyrics as EnrichmentResult.NotFound
        assertEquals("all_providers", lyricsNotFound.provider)
        assertNull("a non-identity type must never carry the identity provider's suggestions", lyricsNotFound.suggestions)
    }

    @Test
    fun `the identity rule holds over the composed stack`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for MusicBrainz's own probe type and one LRCLIB-exclusive type
        val results = engine.enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED))

        // Then - the identity rule holds in its name-search form: this request carries no
        // identifier, so it resolves by name search, which never sets canonical names
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
    }

    @Test
    fun `no upstream URL is requested twice in one enrich`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for MusicBrainz's own probe type and one LRCLIB-exclusive type
        engine.enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED))

        // Then - every upstream URL was fetched at most once
        http.assertNoUrlRequestedTwice()
    }

    private companion object {
        const val SCENARIO = "mb-suggestions-track"
        const val TITLE = "Enter Sandman"
        const val ARTIST = "Metallica"
    }
}
