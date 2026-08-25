package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FakeProvider] is called from [DefaultEnrichmentEngine]'s per-type fan-out — one `launch` per
 * requested type on `Dispatchers.Default`, real threads — so its call log must tolerate concurrent
 * writers or an engine test flakes on whichever type loses the race. `runBlocking`, not `runTest`:
 * the race only exists on a real dispatcher.
 */
// InjectDispatcher: the race under test only exists on real threads; virtual time serialises it away.
@Suppress("InjectDispatcher")
class FakeProviderConcurrencyTest {

    @Test
    fun `concurrent enrich calls all land in the call log`() = runBlocking {
        // Given - the per-type concurrency an engine fan-out subjects one provider to
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val types = listOf(
            EnrichmentType.LABEL, EnrichmentType.GENRE,
            EnrichmentType.RELEASE_DATE, EnrichmentType.COUNTRY,
        )

        repeat(10_000) { trial ->
            val provider = FakeProvider()
            // When - four coroutines on real threads call enrich as simultaneously as a spin
            // barrier can arrange
            val ready = AtomicInteger()
            coroutineScope {
                for (type in types) {
                    launch(Dispatchers.Default) {
                        ready.incrementAndGet()
                        while (ready.get() < types.size) {
                            Thread.onSpinWait()
                        }
                        provider.enrich(request, type)
                    }
                }
            }

            // Then - no call was lost to a racing writer
            assertEquals("trial $trial lost a concurrent enrich call", types.size, provider.enrichCalls.size)
        }
    }
}
