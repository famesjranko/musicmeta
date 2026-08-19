package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduces the cancel-ab-complete.md workload against the shipped code: 10 early-cancelled
 * `enrichProgressive` collections of one request (cancelled at the first non-empty snapshot), then
 * one full collection, real wall-clock delays under a real dispatcher — never `runTest`, whose
 * virtual time cannot see the class of bug complete-and-cache targets. Reproduced stable at 3
 * total provider calls across several manual runs (see the report); asserted here rather than left
 * as a print-only script.
 */
// InjectDispatcher: a real dispatcher, not runTest virtual time, is the point — see the class KDoc.
@Suppress("InjectDispatcher")
class CancelAbWorkloadTest {
    private class TimedProvider(type: EnrichmentType, private val delayMs: Long, id: String) :
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100))) {
        val calls = AtomicInteger(0)
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            calls.incrementAndGet()
            delay(delayMs)
            return EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/${type.name}"), id, 0.9f)
        }
    }

    @Test fun `10 early-cancelled collections then 1 full collection cost 3 provider calls, not 33`() =
        runBlocking(Dispatchers.Default) {
            // Given - one request, three types on three providers of differing speed
            val req = EnrichmentRequest.forArtist("Portishead")
            val fast = TimedProvider(EnrichmentType.SIMILAR_ARTISTS, 50, "fast")
            val slow1 = TimedProvider(EnrichmentType.SIMILAR_TRACKS, 500, "slow1")
            val slow2 = TimedProvider(EnrichmentType.ARTIST_BIO, 500, "slow2")
            val cache = FakeEnrichmentCache()
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(fast, slow1, slow2)), cache, EnrichmentConfig(enableIdentityResolution = false),
            )
            val types = setOf(EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.SIMILAR_TRACKS, EnrichmentType.ARTIST_BIO)

            val t0 = System.currentTimeMillis()
            repeat(10) {
                val job = launch { engine.enrichProgressive(req, types).first { it.raw.isNotEmpty() } }
                job.join()
            }
            val phase1Wall = System.currentTimeMillis() - t0

            withTimeout(5_000) { while (cache.stored.isEmpty()) delay(5) }
            val phase1WithWait = System.currentTimeMillis() - t0

            // When - one final, uncancelled collection follows
            val t1 = System.currentTimeMillis()
            val terminal = engine.enrichProgressive(req, types).toList().last()
            val phase2Wall = System.currentTimeMillis() - t1
            val totalCalls = fast.calls.get() + slow1.calls.get() + slow2.calls.get()

            println("=== cancel-ab-complete workload (musicmeta-core, stage 3) ===")
            println("phase1 wall (10 cancels only): ${phase1Wall}ms")
            println("phase1 wall (incl. wait for write-back): ${phase1WithWait}ms")
            println("provider calls: fast=${fast.calls.get()} slow1=${slow1.calls.get()} slow2=${slow2.calls.get()}")
            println("total provider calls: $totalCalls")
            println("cache entries after phase1: ${cache.stored.size}")
            println("phase2 wall: ${phase2Wall}ms")
            println("phase2 terminal pending: ${types - terminal.raw.keys}")
            println("total wall: ${System.currentTimeMillis() - t0}ms")

            // Then - all ten cancels coalesced onto the one detached run each type ever needed:
            // one call per type across the whole ten-cancel phase, not ten
            assertEquals("expected the coalesced-run count, not one call per cancel", 3, totalCalls)
            // And - phase 1 already wrote every type back, so phase 2 is a pure cache hit
            assertTrue("phase 2 must not have re-asked any provider", phase2Wall < 100)
            assertEquals(emptySet<EnrichmentType>(), types - terminal.raw.keys)
        }
}
