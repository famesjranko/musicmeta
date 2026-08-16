package com.landofoz.musicmeta.harness

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.EntityIdentity
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testkit.countMatching
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composed stack over the `itunes-album-pool` pool: the only right-artist hit for an album
 * request naming `Song` by `David Bowie` is an unrelated Bowie collection. The #210 defect this
 * reproduces is iTunes accepting on artist match alone; the fix added a base-title rejection
 * (`ITunesAlbumSelection.kt:23`), so no album type may select this candidate.
 */
class ItunesAlbumPoolScenarioTest {

    private val albumTypes =
        setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_METADATA, EnrichmentType.ALBUM_TRACKS)

    @Test
    fun `no album type selects the right-artist unrelated collection`() = runTest {
        // Given - the real stack, offline, over a pool whose only iTunes hit shares the artist but
        // names an unrelated collection
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - all three album types are requested together
        val results = engine.enrich(request, albumTypes)

        // Then - no type selects the unrelated collection
        for (type in albumTypes) {
            val result = results.raw[type]
            assertTrue("$type should decline the right-artist unrelated collection, was $result", result !is EnrichmentResult.Success)
        }
    }

    @Test
    fun `the three album types share one resolved decision, not one search each`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - all three album types are requested together
        engine.enrich(request, albumTypes)

        // Then - the album search fires exactly once for all three types — not zero (which would
        // mean iTunes was never asked) and not three (one independently-ranked search per type,
        // the #210 defect's per-call blast radius)
        assertEquals(1, http.countMatching(ALBUM_SEARCH_FRAGMENT))
    }

    @Test
    fun `the identity rule holds over the composed stack`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - all three album types are requested together
        val results = engine.enrich(request, albumTypes)

        // Then - the identity rule holds in its name-search form: this request carries no
        // identifier, so it resolves by name search, which never sets canonical names
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
    }

    @Test
    fun `no upstream URL is requested twice in one enrich`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - all three album types are requested together
        engine.enrich(request, albumTypes)

        // Then - every upstream URL was fetched at most once
        http.assertNoUrlRequestedTwice()
    }

    private companion object {
        const val SCENARIO = "itunes-album-pool"
        const val TITLE = "Song"
        const val ARTIST = "David Bowie"
        const val ALBUM_SEARCH_FRAGMENT = "search?media=music&entity=album"
    }
}
