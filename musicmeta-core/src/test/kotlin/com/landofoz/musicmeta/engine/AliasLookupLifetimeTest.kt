package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Who owns the alias lookup once it stops being the reader's. It runs on the engine's detached
 * scope so one provider's cancellation cannot take it from the others, which puts two lifetimes on
 * the engine rather than on the coroutine that opened it: the call must abandon it as its context is
 * torn down when it returns, and `close()` must reach whatever is still running when the engine goes.
 *
 * Neither is observable from a result — a hung source degrades to an empty pool either way — so the
 * fake reports its own cancellation.
 */
class AliasLookupLifetimeTest {

    /** Threads of this class's own, so a neighbour's detached run cannot starve this fan-out. */
    private val fanOut = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
        .asCoroutineDispatcher()

    @After fun closeFanOut() {
        fanOut.close()
    }

    @Test
    fun `a source still running when the call returns is abandoned with the call`() = runTest {
        // Given - a provider that gives up on the pool long before the call's own budget does
        val abandoned = CountDownLatch(1)
        val engine = engine(
            hangingAliasProvider(abandoned, CountDownLatch(1), readTimeoutMs = 100),
            enrichTimeoutMs = 60_000,
        )

        // When - the call settles its one type and returns with the lookup still in flight
        engine.enrich(REQUEST, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the lookup was cancelled as the call's context came down, not left on its budget
        assertTrue(
            "the lookup outlived the call that opened it",
            abandoned.await(GRACE_MS, TimeUnit.MILLISECONDS),
        )
        engine.close()
    }

    @Test
    fun `close reaches a source still running on the engine's detached scope`() = runTest {
        // Given - a call whose alias source is still running, under a deadline far too long to fire
        val abandoned = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val engine = engine(hangingAliasProvider(abandoned, entered), enrichTimeoutMs = 60_000)
        val call = launch(fanOut) { engine.enrich(REQUEST, setOf(EnrichmentType.SIMILAR_ARTISTS)) }
        assertTrue("the source was never reached", entered.await(GRACE_MS, TimeUnit.MILLISECONDS))

        // When - the engine is closed while that lookup is in flight
        engine.close()

        // Then - closing the detached scope reached the lookup running on it
        assertTrue(
            "close() could not see the lookup's job",
            abandoned.await(GRACE_MS, TimeUnit.MILLISECONDS),
        )
        call.cancel()
    }

    /**
     * A provider that offers an alias source that never answers and then reads the pool — the
     * shape every name-search provider has, with the upstream stalled.
     */
    private fun hangingAliasProvider(
        abandoned: CountDownLatch,
        entered: CountDownLatch,
        readTimeoutMs: Long? = null,
    ) = object : FakeProvider(
        id = "hanging",
        capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
    ) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            currentCoroutineContext()[ResolvedEntityNames]?.offerAliases {
                entered.countDown()
                suspendCancellableCoroutine { it.invokeOnCancellation { abandoned.countDown() } }
            }
            if (readTimeoutMs == null) resolvedAliasPool() else withTimeoutOrNull(readTimeoutMs) { resolvedAliasPool() }
            return EnrichmentResult.NotFound(type, id)
        }
    }

    private fun engine(
        provider: FakeProvider,
        enrichTimeoutMs: Long,
        detachedDispatcher: CoroutineDispatcher = fanOut,
    ) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = enrichTimeoutMs),
        detachedDispatcher = detachedDispatcher,
    )

    private companion object {
        val REQUEST = EnrichmentRequest.forArtist("Radiohead")

        /** Wall-clock slack for a latch that should already be down, not a deadline being waited on. */
        const val GRACE_MS = 5_000L
    }
}
