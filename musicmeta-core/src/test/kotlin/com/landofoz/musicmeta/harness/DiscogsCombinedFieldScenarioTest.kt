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
 * The composed stack over the `discogs-combined-field` pool: Discogs names an artist and a release
 * title in one combined `"Artist - Title"` search field. The only hit for an album request naming
 * `Song` by `David Bowie` combines the requested artist with an unrelated title
 * (`Alabama Song`). The #210 defect this reproduces is accepting on the parsed artist half alone;
 * the fix added a title-equivalence floor on the parsed title half (`DiscogsAlbumSelection.kt:75`),
 * so no album type may select this release.
 */
class DiscogsCombinedFieldScenarioTest {

    // Discogs shares one album scope across ALBUM_ART, LABEL, RELEASE_TYPE and ALBUM_METADATA
    // (DiscogsProvider.albumScope's KDoc) — it declares no ALBUM_TRACKS capability at all.
    private val albumTypes = setOf(
        EnrichmentType.ALBUM_ART, EnrichmentType.LABEL,
        EnrichmentType.RELEASE_TYPE, EnrichmentType.ALBUM_METADATA,
    )

    /**
     * The subset of [albumTypes] this pool can actually decide — the ones whose payload it fills.
     * `RELEASE_TYPE` is absent because the pool carries no format/type field, so asserting
     * selection over it would pin that an under-specified fixture yields nothing, which says
     * nothing about the product.
     *
     * **Pinned by `the pool decides exactly the album types this scenario asserts over`**, so
     * enriching the pool until `RELEASE_TYPE` answers fails that test rather than silently leaving
     * this loop under-covering. A limitation recorded only in prose decays; one asserted cannot.
     */
    private val decidableTypes = setOf(
        EnrichmentType.ALBUM_ART, EnrichmentType.LABEL, EnrichmentType.ALBUM_METADATA,
    )

    @Test
    fun `the pool decides exactly the album types this scenario asserts over`() = runTest {
        // Given - the same pool, asked for the release it actually names, so acceptance succeeds
        // and each type answers iff this pool carries the fields that type reads
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(POOL_RELEASE_TITLE, ARTIST)

        // When - every type Discogs shares its album scope across is requested against the entity
        // the pool does name
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
    fun `no album type selects the combined-field release under the artist prefix`() = runTest {
        // Given - the real stack, offline, over a pool whose only Discogs hit combines the
        // requested artist with an unrelated title
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - every type Discogs shares its album scope across is requested together
        val results = engine.enrich(request, albumTypes)

        // Then - no type this pool can decide selects the release
        assertTrue("no decidable type to assert over", decidableTypes.isNotEmpty())
        for (type in decidableTypes) {
            val result = results.raw[type]
            assertTrue("$type should decline the combined-field release, was $result", result !is EnrichmentResult.Success)
        }
    }

    @Test
    fun `the shared album types resolve one decision, not one search each`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - every type Discogs shares its album scope across is requested together
        engine.enrich(request, albumTypes)

        // Then - the release search fires exactly once for all four types — not zero (which would
        // mean Discogs was never asked) and not four (one independently-ranked search per type,
        // the #210 defect's per-call blast radius)
        assertEquals(1, http.countMatching(RELEASE_SEARCH_FRAGMENT))
    }

    @Test
    fun `the identity rule holds over the composed stack`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(TITLE, ARTIST)

        // When - every type Discogs shares its album scope across is requested together
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

        // When - every type Discogs shares its album scope across is requested together
        engine.enrich(request, albumTypes)

        // Then - every upstream URL was fetched at most once
        http.assertNoUrlRequestedTwice()
    }

    private companion object {
        const val SCENARIO = "discogs-combined-field"
        const val POOL_RELEASE_TITLE = "Alabama Song"
        const val TITLE = "Song"
        const val ARTIST = "David Bowie"
        const val RELEASE_SEARCH_FRAGMENT = "database/search"
    }
}
