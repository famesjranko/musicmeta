package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Staff-final-review probes: cross-stage seam intersections no single stage owned. Each test
 * asserts a promise the shipped KDoc/glossary makes; a red run here is a defect demonstration,
 * not a mutation replay. The last test is not from that review — it pins the coalescing
 * [progressiveDedupeKey] must still do (two identical calls attach to one run) alongside the four
 * defect probes above, so a fix for the others cannot regress it to zero-coalescing.
 */
class StaffFinalProbesTest {
    private lateinit var cache: FakeEnrichmentCache
    private val req = EnrichmentRequest.forArtist("Radiohead")

    @Before fun setup() { cache = FakeEnrichmentCache() }

    private fun artSuccess(type: EnrichmentType, provider: String = "p") =
        EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/${type.name}"), provider, 0.95f)

    /** A provider whose ARTIST_BIO answer parks until [gate] releases, signalling [started] first. */
    private class GatedBioProvider(
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : FakeProvider(
        id = "p",
        capabilities = listOf(
            ProviderCapability(EnrichmentType.ARTIST_BIO, 100),
            ProviderCapability(EnrichmentType.ALBUM_ART, 100),
        ),
    ) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            if (type == EnrichmentType.ARTIST_BIO) {
                started.complete(Unit)
                gate.await()
            }
            return EnrichmentResult.Success(
                type, EnrichmentData.Artwork("https://x/${type.name}"), id, 0.95f,
            )
        }
    }

    @Test
    fun `a narrower call sharing an uncached set with a wider in-flight run keeps its own requestedTypes`() = runTest {
        // Given - ALBUM_ART is cached, so a wide {ALBUM_ART, ARTIST_BIO} call and a narrow
        // {ARTIST_BIO} call share the same *uncached* set even though their requested sets differ
        // (the dedupe key is on requested types now, so these two start separate runs — this pins
        // that each caller's own result is still shaped by its own request, not by whichever run,
        // if any, it happened to share)
        val provider = GatedBioProvider()
        cache.put(
            entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART,
            artSuccess(EnrichmentType.ALBUM_ART), CanonicalStatus.RESOLVED,
        )
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )
        val wide = launch {
            engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ARTIST_BIO)).toList()
        }
        provider.started.await()

        // When - the narrow call attaches to the wide call's in-flight run, then the run completes
        val narrow = async { engine.enrich(req, setOf(EnrichmentType.ARTIST_BIO)) }
        testScheduler.advanceUntilIdle()
        provider.gate.complete(Unit)
        val narrowResult = narrow.await()
        wide.join()

        // Then - the narrow caller's result is shaped by its own request, not the run starter's
        assertEquals(
            "a call for {ARTIST_BIO} must report requestedTypes {ARTIST_BIO}, not the run starter's set",
            setOf(EnrichmentType.ARTIST_BIO), narrowResult.requestedTypes,
        )
    }

    @Test
    fun `a wider call sharing an uncached set with a narrower in-flight run still gets every type it asked for`() = runTest {
        // Given - ALBUM_ART is cached, a narrow {ARTIST_BIO} run is already in flight, and a wide
        // {ALBUM_ART, ARTIST_BIO} call computes the same *uncached* set (just {ARTIST_BIO}) even
        // though its requested set is wider (the dedupe key is on requested types now, so these two
        // start separate runs — this pins that the wide caller's own cache read still reaches it)
        val provider = GatedBioProvider()
        cache.put(
            entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART,
            artSuccess(EnrichmentType.ALBUM_ART), CanonicalStatus.RESOLVED,
        )
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )
        val narrow = launch {
            engine.enrichProgressive(req, setOf(EnrichmentType.ARTIST_BIO)).toList()
        }
        provider.started.await()

        // When - the wide call attaches to the narrow run, then the run completes
        val wide = async { engine.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ARTIST_BIO)) }
        testScheduler.advanceUntilIdle()
        provider.gate.complete(Unit)
        val wideResult = wide.await()
        narrow.join()

        // Then - the wide caller sees its cached ALBUM_ART, not a snapshot missing a requested type
        assertTrue(
            "a call that asked for ALBUM_ART must have an ALBUM_ART entry, got ${wideResult.raw.keys}",
            EnrichmentType.ALBUM_ART in wideResult.raw,
        )
    }

    @Test
    fun `a stale-substituted serve never replays a persisted isCatalogDegraded true`() = runTest {
        // Given - an expired SIMILAR_ARTISTS entry persisted with isCatalogDegraded=true (written
        // by a call whose CatalogProvider threw), a provider now failing live, STALE_IF_ERROR, and
        // no CatalogProvider configured this call (UNFILTERED-equivalent: never true per the KDoc)
        val type = EnrichmentType.SIMILAR_ARTISTS
        val key = entityKeyFor(req, type)
        cache.expiredStore["$key:$type"] = EnrichmentResult.Success(
            type,
            EnrichmentData.SimilarArtists(listOf(SimilarArtist(name = "Portishead", matchScore = 0.9f))),
            "old", 0.9f, isCatalogDegraded = true,
        )
        val provider = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(type, 100)))
            .also { it.givenResult(type, EnrichmentResult.Error(type, "p", "API down")) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)), cache,
            EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR),
        )

        // When - the live fetch fails and the stale entry is substituted
        val result = engine.enrich(req, setOf(type))
        val served = result.raw.getValue(type) as EnrichmentResult.Success

        // Then - the serve is stale, and the call-scoped flag was normalized for this call rather
        // than replaying the value persisted at write time
        assertTrue("precondition: the stale entry must have been substituted", served.isStale)
        assertTrue(
            "isCatalogDegraded is call-scoped and this call never attempted filtering, so it must be false",
            !served.isCatalogDegraded,
        )
    }

    @Test
    fun `a closed engine's unsettled type is stamped ENGINE_CLOSED even under STALE_IF_ERROR`() = runTest {
        // Given - a closed engine, STALE_IF_ERROR, and an expired cache entry for the requested type
        val type = EnrichmentType.ALBUM_ART
        val key = entityKeyFor(req, type)
        cache.expiredStore["$key:$type"] = artSuccess(type, provider = "old")
        val provider = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(type, 100)))
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)), cache,
            EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR),
        )
        engine.close()

        // When - a never-seen key is asked for after close()
        val result = engine.enrich(req, setOf(type))

        // Then - the type is an Error(ENGINE_CLOSED), the per-type completeness close()'s own KDoc
        // and the glossary promise, not a stale Success silently substituted after shutdown
        val entry = result.raw.getValue(type)
        assertTrue(
            "close() promises Error(ENGINE_CLOSED) for an unsettled type, got $entry",
            entry is EnrichmentResult.Error && entry.errorKind == ErrorKind.ENGINE_CLOSED,
        )
    }

    @Test
    fun `two calls with identical requested types over the same cache state still coalesce onto one run`() =
        runTest {
            // Given - ARTIST_BIO is uncached, and a provider that counts how many times it is asked
            val calls = AtomicInteger(0)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val provider = object : FakeProvider(
                id = "p",
                capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
            ) {
                override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                    return EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/${type.name}"), id, 0.95f)
                }
            }
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false),
            )
            val first = launch { engine.enrichProgressive(req, setOf(EnrichmentType.ARTIST_BIO)).toList() }
            started.await()

            // When - a second call for the identical requested type set arrives while the first is
            // still in flight
            val second = async { engine.enrich(req, setOf(EnrichmentType.ARTIST_BIO)) }
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            val secondResult = second.await()
            first.join()

            // Then - the provider was asked once, not twice: the two calls attached to the same run
            assertEquals(
                "two calls for the identical requested type set must still coalesce onto one run",
                1, calls.get(),
            )
            assertEquals(setOf(EnrichmentType.ARTIST_BIO), secondResult.requestedTypes)
        }
}
