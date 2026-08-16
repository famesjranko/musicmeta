package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.engine.observedProvenance
import com.landofoz.musicmeta.engine.stampContributorProvenance
import com.landofoz.musicmeta.engine.stampProvenance
import com.landofoz.musicmeta.testkit.ContractSuite
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testkit.eachProviderCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract every [EnrichmentResult.Success] reaching a consumer owes its caller: a non-null
 * [EnrichmentResult.Success.provenance] naming the route that selected it, asserted once here and
 * inherited by the concrete engine subclass. The subject is the **engine**
 * ([TestStack.build]), not a provider — a per-provider subject would need eleven subclasses to say
 * one thing (`architecture.md` § 2 P3).
 *
 * Three call sites fill the field when a result arrives without one:
 * [com.landofoz.musicmeta.engine.stampProvenance] (the single-winner chain path) and
 * [com.landofoz.musicmeta.engine.stampContributorProvenance] (each contributor to a merged type),
 * both classifying through [com.landofoz.musicmeta.engine.observedProvenance]. A provider that sets
 * its own route on the `Success` it returns is trusted verbatim and never reaches either.
 */
abstract class ProviderProvenanceContract : ContractSuite<EnrichmentEngine>() {

    // ---- B: observedProvenance is exhaustive over every IdentifierRequirement × CanonicalStatus ----

    @Test
    fun `observedProvenance covers every IdentifierRequirement crossed with every CanonicalStatus`() {
        // Given - every declared IdentifierRequirement plus the null "no winning chain" case
        // (a merge contributor, or a composite dependency with no chain of its own), enumerated from
        // the entries themselves so a requirement added tomorrow is covered without editing this test
        val requirements: List<IdentifierRequirement?> = IdentifierRequirement.entries + listOf(null)

        for (requirement in requirements) {
            for (status in CanonicalStatus.entries) {
                // When - the route is derived for this one combination
                val provenance = observedProvenance(requirement, status)

                // Then - the mapped route matches EntityKey.kt's declared rule: a requirement naming
                // a MusicBrainz id space is CANONICAL_ID; one naming exactly one other provider-owned
                // id space is PROVIDER_NATIVE_ID; NONE, ANY_IDENTIFIER and no winning chain at all
                // fall back to MusicBrainz's own canonical confirmation of this call
                val expected = when (requirement) {
                    IdentifierRequirement.MUSICBRAINZ_ID, IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID ->
                        LookupProvenance.CANONICAL_ID
                    IdentifierRequirement.WIKIDATA_ID, IdentifierRequirement.WIKIPEDIA_TITLE ->
                        LookupProvenance.PROVIDER_NATIVE_ID
                    IdentifierRequirement.NONE, IdentifierRequirement.ANY_IDENTIFIER, null ->
                        if (status == CanonicalStatus.RESOLVED) LookupProvenance.EXACT_NAME else LookupProvenance.FUZZY_NAME
                }
                assertEquals(
                    "requirement=$requirement status=$status",
                    expected,
                    provenance,
                )
            }
        }
    }

    // ---- C: both stamp functions fill only a null provenance, verbatim otherwise ----

    @Test
    fun `stampProvenance fills only a null provenance and leaves a self-reported one untouched`() {
        // Given - one Success that already reports its own route and one with none, no chain
        // evidence for either type
        val results = mutableMapOf<EnrichmentType, EnrichmentResult>(
            EnrichmentType.TRACK_METADATA to success(EnrichmentType.TRACK_METADATA, LookupProvenance.EXTERNAL_CATALOG_ID),
            EnrichmentType.LYRICS_PLAIN to success(EnrichmentType.LYRICS_PLAIN, provenance = null),
        )

        // When - the engine's post-resolution stamp runs
        stampProvenance(results, CanonicalStatus.UNRESOLVED, emptyMap())

        // Then - the self-reported route survives verbatim and the null one is filled, never CACHE
        assertEquals(
            LookupProvenance.EXTERNAL_CATALOG_ID,
            (results.getValue(EnrichmentType.TRACK_METADATA) as EnrichmentResult.Success).provenance,
        )
        assertEquals(
            LookupProvenance.FUZZY_NAME,
            (results.getValue(EnrichmentType.LYRICS_PLAIN) as EnrichmentResult.Success).provenance,
        )
    }

    @Test
    fun `stampContributorProvenance fills only a null provenance and leaves a self-reported one untouched`() {
        // Given - one merge contributor that already reports its own route and one with none, no
        // registered chain to consult for either
        val selfReported = success(EnrichmentType.GENRE, LookupProvenance.EXACT_NAME)
        val unstamped = success(EnrichmentType.GENRE, provenance = null)

        // When - each contributor is stamped independently, the way a collect-all merge walk does it
        val stampedSelfReported = stampContributorProvenance(selfReported, chain = null, CanonicalStatus.UNRESOLVED)
        val stampedUnstamped = stampContributorProvenance(unstamped, chain = null, CanonicalStatus.UNRESOLVED)

        // Then - the self-reported route survives verbatim and the null one is filled, never left null
        assertEquals(LookupProvenance.EXACT_NAME, stampedSelfReported.provenance)
        assertEquals(LookupProvenance.FUZZY_NAME, stampedUnstamped.provenance)
    }

    // ---- D: live/cached agreement, both arms ----

    @Test
    fun `a preserving cache replays the live provenance verbatim`() = runTest {
        // Given - the composed engine over a pool whose LRCLIB hit is the requested track, backed
        // by the default preserving cache. A caller-supplied mbid on a NONE-requirement-only
        // request makes identity resolution unnecessary (never NOT_ATTEMPTED_NOT_REQUIRED's
        // uncacheable siblings AMBIGUOUS/UNRESOLVED/FAILED), so write-back actually populates the
        // cache instead of silently declining to cache an unconfirmed identity
        val http = UpstreamPools.load(LYRICS_SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(LYRICS_TITLE, LYRICS_ARTIST, mbid = UNCONSULTED_MBID)

        // When - the same request is enriched twice: once live, once served from cache
        val live = engine.enrich(request, setOf(EnrichmentType.LYRICS_SYNCED))
        val urlsAfterLive = http.requestedUrls.size
        val cached = engine.enrich(request, setOf(EnrichmentType.LYRICS_SYNCED))

        // Then - the second call went to the cache rather than upstream. Without this the rule below
        // holds just as well for two identical live lookups, so a write-back that silently stopped
        // caching would leave it green while proving nothing about a cache at all
        assertEquals(
            "the second enrich must be served from cache, not refetched upstream",
            urlsAfterLive,
            http.requestedUrls.size,
        )

        // Then - the cached call reports the exact route the live call observed, never CACHE — a
        // preserving cache has no reason to invoke the engine's own-recovery fallback
        val liveResult = live.raw[EnrichmentType.LYRICS_SYNCED] as EnrichmentResult.Success
        val cachedResult = cached.raw[EnrichmentType.LYRICS_SYNCED] as EnrichmentResult.Success
        assertEquals(liveResult.provenance, cachedResult.provenance)
        assertTrue(
            "a preserving cache must never fall back to CACHE: $cachedResult",
            cachedResult.provenance != LookupProvenance.CACHE,
        )
    }

    @Test
    fun `a non-preserving cache yields CACHE on a hit, never null`() = runTest {
        // Given - the same one-Success pool, backed by a cache that deliberately drops provenance
        // across its own put-get round trip, and the same uncacheable-identity workaround as above
        val http = UpstreamPools.load(LYRICS_SCENARIO)
        val engine = TestStack.build(http, cache = NonPreservingEnrichmentCache())
        val request = EnrichmentRequest.forTrack(LYRICS_TITLE, LYRICS_ARTIST, mbid = UNCONSULTED_MBID)

        // When - the same request is enriched twice: the write-back after the first call feeds the
        // second call's read
        engine.enrich(request, setOf(EnrichmentType.LYRICS_SYNCED))
        val cached = engine.enrich(request, setOf(EnrichmentType.LYRICS_SYNCED))

        // Then - the cache hit reports CACHE — the invariant DefaultEnrichmentEngine.kt's fallback
        // exists for an implementation that cannot recover the original route — and never null
        val cachedResult = cached.raw[EnrichmentType.LYRICS_SYNCED] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CACHE, cachedResult.provenance)
    }

    // ---- E: the enumerated end-to-end rule ----

    @Test
    fun `no Success reaches a consumer with null provenance, over the composed stack`() = runTest {
        // Given - the composed engine, and every type any registered provider declares a capability
        // for, read from the engine rather than hand-listed
        val engine = subject()
        val request = EnrichmentRequest.forTrack(LYRICS_TITLE, LYRICS_ARTIST)
        val types = eachProviderCapability(engine).map { it.second.type }.toSet()

        // When - enriching for every declared capability's type in one call
        val results = engine.enrich(request, types)

        // Then - LRCLIB's hit is a genuine, non-merged Success (proving this pool can distinguish
        // the rule from a merger's own fallback, which never leaves a merged result's provenance
        // null regardless of whether a contributor was stamped), and no Success anywhere is null
        val lyricsResult = results.raw[EnrichmentType.LYRICS_SYNCED]
        assertTrue(
            "expected a non-mergeable, single-provider Success from LRCLIB's exact hit, got $lyricsResult",
            lyricsResult is EnrichmentResult.Success,
        )
        val unstamped = results.raw.values
            .filterIsInstance<EnrichmentResult.Success>()
            .filter { it.provenance == null }
        assertTrue("Success results with no provenance: ${unstamped.map { it.type }}", unstamped.isEmpty())
    }

    // ---- F: the route cross-check ----

    @Test
    fun `a name route implies a search endpoint was requested`() = runTest {
        // Given - the composed engine over the LRCLIB qualifier-match pool
        val http = UpstreamPools.load(LYRICS_SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(LYRICS_TITLE, LYRICS_ARTIST)

        // When - enriching for the type LRCLIB answers by search
        val results = engine.enrich(request, setOf(EnrichmentType.LYRICS_SYNCED))

        // Then - the result claims one of the three name routes, and a search endpoint (never the
        // exact-match /api/get endpoint) is what actually answered it
        val result = results.raw[EnrichmentType.LYRICS_SYNCED] as EnrichmentResult.Success
        assertTrue("expected a name route, got ${result.provenance}", result.provenance in NAME_ROUTES)
        assertTrue(
            "a name route must be backed by a search request: ${http.requestedUrls}",
            http.requestedUrls.any { it.contains("/api/search") },
        )
    }

    @Test
    fun `an id route implies an identifier endpoint was requested`() = runTest {
        // Given - the composed engine over the iTunes barcode pool
        val http = UpstreamPools.load(BARCODE_SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forAlbum(
            title = "Discovery",
            artist = "Daft Punk",
            identifiers = EnrichmentIdentifiers(barcode = "724384960650"),
        )

        // When - enriching for the type iTunes answers by a direct barcode lookup
        val results = engine.enrich(request, setOf(EnrichmentType.ALBUM_METADATA))

        // Then - the result claims one of the three id routes, and a direct identifier endpoint
        // (never a /search) is what actually answered it
        val result = results.raw[EnrichmentType.ALBUM_METADATA] as EnrichmentResult.Success
        assertTrue("expected an id route, got ${result.provenance}", result.provenance in ID_ROUTES)
        assertTrue(
            "an id route must be backed by an identifier request: ${http.requestedUrls}",
            http.requestedUrls.any { it.contains("upc=") },
        )
    }

    private fun success(type: EnrichmentType, provenance: LookupProvenance?) = EnrichmentResult.Success(
        type = type,
        data = EnrichmentData.Metadata(),
        provider = "contract-test",
        confidence = 1f,
        provenance = provenance,
    )

    /**
     * An [EnrichmentCache] that deliberately drops [EnrichmentResult.Success.provenance] across a
     * put-get round trip — the case [LookupProvenance.CACHE]'s own KDoc describes for a third-party
     * implementation that cannot recover the original route. Both shipped caches preserve the field
     * (Room's `lookup_provenance` column, `InMemoryEnrichmentCache`'s stored `Success`); this exists
     * only to exercise the engine's fallback for one that does not.
     */
    private class NonPreservingEnrichmentCache : EnrichmentCache {
        private val entries = mutableMapOf<String, EnrichmentResult.Success>()
        private val manualSelections = mutableSetOf<String>()

        private fun key(entityKey: String, type: EnrichmentType) = "$entityKey:$type"

        override suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? =
            entries[key(entityKey, type)]?.let { CacheEnvelope(it, CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT) }

        override suspend fun put(
            entityKey: String,
            type: EnrichmentType,
            result: EnrichmentResult.Success,
            canonicalStatus: CanonicalStatus,
            ttlMs: Long,
        ) {
            entries[key(entityKey, type)] = result.copy(provenance = null)
        }

        override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
            if (type != null) {
                entries.remove(key(entityKey, type))
            } else {
                entries.keys.removeAll { it.startsWith("$entityKey:") }
            }
        }

        override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) =
            key(entityKey, type) in manualSelections

        override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
            manualSelections.add(key(entityKey, type))
        }

        override suspend fun clear() {
            entries.clear()
            manualSelections.clear()
        }
    }

    private companion object {
        const val LYRICS_SCENARIO = "lrclib-qualifier-match"
        const val LYRICS_TITLE = "Starman - 2012 Remaster"
        const val LYRICS_ARTIST = "David Bowie"

        /**
         * An mbid LRCLIB never consults (it takes no [IdentifierRequirement], searching by name
         * regardless), present only so [needsIdentityResolution] sees a request with nothing left
         * to resolve for a LYRICS_SYNCED-only call and skips MusicBrainz — landing
         * [CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED], the write-back-eligible status a request
         * with no identifier at all would instead resolve to AMBIGUOUS/UNRESOLVED/FAILED under.
         */
        const val UNCONSULTED_MBID = "00000000-0000-0000-0000-000000000000"
        const val BARCODE_SCENARIO = "itunes-barcode-lookup"
        val ID_ROUTES = setOf(
            LookupProvenance.CANONICAL_ID,
            LookupProvenance.PROVIDER_NATIVE_ID,
            LookupProvenance.EXTERNAL_CATALOG_ID,
        )
        val NAME_ROUTES = setOf(
            LookupProvenance.EXACT_NAME,
            LookupProvenance.QUALIFIER_FALLBACK_NAME,
            LookupProvenance.FUZZY_NAME,
        )
    }
}
