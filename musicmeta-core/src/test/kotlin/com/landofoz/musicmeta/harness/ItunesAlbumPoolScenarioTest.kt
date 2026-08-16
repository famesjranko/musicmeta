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

    /**
     * The subset of [albumTypes] this pool can actually decide — the ones whose payload it fills.
     * Asserting selection over a type the pool could never answer is an assertion that cannot fail:
     * it pins that an under-specified fixture yields nothing, which says nothing about the product.
     *
     * **Pinned by `the pool decides exactly the album types this scenario asserts over`**, so
     * enriching the pool until another type answers fails that test rather than silently leaving
     * this loop under-covering. A limitation recorded only in prose decays; one asserted cannot.
     */
    private val decidableTypes = setOf(EnrichmentType.ALBUM_ART)

    @Test
    fun `the pool decides exactly the album types this scenario asserts over`() = runTest {
        // Given - the same pool, asked for the collection it actually names, so acceptance succeeds
        // and each type answers iff this pool carries the fields that type reads
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(POOL_COLLECTION, ARTIST)

        // When - all three album types are requested against the entity the pool does name
        val results = engine.enrich(request, albumTypes)

        // Then - exactly the declared subset answers; widen decidableTypes when this widens
        val answered = albumTypes.filter { results.raw[it] is EnrichmentResult.Success }.toSet()
        assertEquals(
            "the pool's decidable album types changed — widen or narrow decidableTypes to match, " +
                "and widen the selection loop with it",
            decidableTypes,
            answered,
        )
    }

    @Test
    fun `no album type selects the right-artist unrelated collection`() = runTest {
        // Given - the real stack, offline, over a pool whose only iTunes hit shares the artist but
        // names an unrelated collection
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - all three album types are requested together
        val results = engine.enrich(request, albumTypes)

        // Then - no type this pool can decide selects the unrelated collection
        assertTrue("no decidable type to assert over", decidableTypes.isNotEmpty())
        for (type in decidableTypes) {
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
        const val POOL_COLLECTION = "The Rise and Fall of Ziggy Stardust"
    }
}
