package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProviderChainTest {
    private val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
    private fun art(p: String) = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x.com/$p.jpg"), p, 0.95f)

    @Test fun `resolve tries providers in priority order`() = runTest {
        // Given — p1 is higher priority than p2, both have art
        val p1 = FakeProvider(id = "p1").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p1")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolve(req)

        // Then — p1 wins, p2 never called
        assertEquals("p1", (result as EnrichmentResult.Success).provider)
        assertEquals(0, p2.enrichCalls.size)
    }

    @Test fun `resolve falls through NotFound`() = runTest {
        // Given — p1 returns NotFound, p2 has art
        val p1 = FakeProvider(id = "p1").also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "p1")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolve(req)

        // Then — falls through to p2
        assertEquals("p2", (result as EnrichmentResult.Success).provider)
    }

    @Test fun `resolve falls through RateLimited`() = runTest {
        // Given — p1 is rate limited, p2 has art
        val p1 = FakeProvider(id = "p1").also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.RateLimited(EnrichmentType.ALBUM_ART, "p1")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolve(req)

        // Then — falls through to p2
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test fun `resolve falls through Error`() = runTest {
        // Given — p1 returns error, p2 has art
        val p1 = FakeProvider(id = "p1").also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p1", "err")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolve(req)

        // Then — falls through to p2
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test fun `resolve skips unavailable providers`() = runTest {
        // Given — p1 is unavailable (missing API key), p2 is available
        val p1 = FakeProvider(id = "p1", isAvailable = false).also { it.givenResult(EnrichmentType.ALBUM_ART, art("p1")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolve(req)

        // Then — p1 skipped entirely, p2 used
        assertEquals("p2", (result as EnrichmentResult.Success).provider)
        assertEquals(0, p1.enrichCalls.size)
    }

    @Test fun `resolve skips providers needing identifiers when missing`() = runTest {
        // Given — p1 requires MBID (request has none), p2 doesn't
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, requiresIdentifier = true)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p1")) }
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving without identifiers
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolve(req)

        // Then — p1 skipped, p2 used
        assertEquals("p2", (result as EnrichmentResult.Success).provider)
    }

    @Test fun `resolve returns NotFound when all providers exhausted`() = runTest {
        // Given — only provider returns NotFound
        val p = FakeProvider(id = "p").also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "p")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p)).resolve(req)

        // Then — NotFound with "all_providers"
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test fun `resolve catches exceptions and falls through`() = runTest {
        // Given — first provider throws, second has art
        val crasher = object : FakeProvider(id = "crash") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType) = throw RuntimeException("boom")
        }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(crasher, p2)).resolve(req)

        // Then — exception caught, falls through to p2
        assertTrue(result is EnrichmentResult.Success)
    }

    // --- Circuit breaker integration ---

    @Test fun `resolve skips provider with open circuit breaker`() = runTest {
        // Given — p1's circuit is open (1 failure, threshold=1), p2 is healthy
        val p1 = FakeProvider(id = "p1").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p1")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }
        val breakers = mapOf(
            "p1" to CircuitBreaker(failureThreshold = 1).also { it.recordFailure() },
            "p2" to CircuitBreaker(),
        )

        // When — resolving
        val result = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2), breakers).resolve(req) as EnrichmentResult.Success

        // Then — p1 skipped (open circuit), p2 used
        assertEquals("p2", result.provider)
        assertEquals(0, p1.enrichCalls.size)
    }

    @Test fun `resolve records success on circuit breaker and resets failures`() = runTest {
        // Given — breaker has 2 consecutive failures (below threshold of 5)
        val breaker = CircuitBreaker(failureThreshold = 5)
        breaker.recordFailure()
        breaker.recordFailure()
        val p = FakeProvider(id = "p").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }

        // When — provider succeeds
        ProviderChain(EnrichmentType.ALBUM_ART, listOf(p), mapOf("p" to breaker)).resolve(req)

        // Then — failure count reset to 0
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `resolve records failure on error result`() = runTest {
        // Given — breaker with threshold of 2, starts clean
        val breaker = CircuitBreaker(failureThreshold = 2)
        val p1 = FakeProvider(id = "p1").also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p1", "err")) }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — p1 errors
        ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2), mapOf("p1" to breaker, "p2" to CircuitBreaker())).resolve(req)

        // Then — 1 failure recorded, still below threshold (CLOSED)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `NotFound does not trip circuit breaker`() = runTest {
        // Given — breaker with threshold of 1 (would open on any failure)
        val breaker = CircuitBreaker(failureThreshold = 1)
        val p1 = FakeProvider(id = "p1") // Default: returns NotFound
        val p2 = FakeProvider(id = "p2").also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — p1 returns NotFound
        ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2), mapOf("p1" to breaker, "p2" to CircuitBreaker())).resolve(req)

        // Then — NotFound treated as success (API worked, just no data), circuit stays closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }
}
