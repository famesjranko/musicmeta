package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class BreakerAdmissionObservationTest {
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `observational checks leave a half-open permit for resolve`() = runTest {
        // Given - a provider whose shared breaker is ready for one recovery probe
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        breaker.recordFailure()
        time.set(100L)
        val provider = object : FakeProvider(
            id = "p1",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, priority = 1)),
        ) {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf(provider.id to breaker))

        // When - a best-effort gate observes eligibility before the chain owns an actual attempt
        repeat(3) { assertTrue(breaker.allowRequest()) }
        val first = chain.resolve(request)
        val retry = chain.resolve(request)

        // Then - exactly one live call closed the breaker and the later request can run normally
        assertTrue(first is EnrichmentResult.NotFound)
        assertTrue(retry is EnrichmentResult.NotFound)
        assertEquals(2, provider.enrichCalls.size)
    }

    @Test fun `registry checks do not consume a half-open permit before its chain resolves`() = runTest {
        // Given - a registry whose provider is ready for one recovery probe
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        breaker.recordFailure()
        time.set(100L)
        val provider = object : FakeProvider(
            id = "p1",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, priority = 1)),
        ) {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val registry = ProviderRegistry(listOf(provider), circuitBreakerFactory = { breaker })

        // When - the best-effort registry gate is checked before the real chain attempt
        repeat(3) { assertTrue(registry.allowsRequest(provider.id)) }
        val result = requireNotNull(registry.chainFor(EnrichmentType.ALBUM_ART)).resolve(request)

        // Then - the chain still owns and settles the one live recovery probe
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(1, provider.enrichCalls.size)
        assertTrue(registry.allowsRequest(provider.id))
    }

    @Test fun `observational checks leave a half-open permit for resolveAll`() = runTest {
        // Given - a merge chain whose shared breaker is ready for one recovery probe
        val time = AtomicLong(0L)
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        breaker.recordFailure()
        time.set(100L)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(provider), mapOf(provider.id to breaker))

        // When - a best-effort gate observes eligibility before the merge walk owns the probe
        repeat(3) { assertTrue(breaker.allowRequest()) }
        val first = chain.resolveAll(request)
        val retry = chain.resolveAll(request)

        // Then - one live probe closes the breaker and a later merge walk is admitted
        assertTrue(first.failure == null)
        assertTrue(retry.failure == null)
        assertEquals(2, provider.enrichCalls.size)
    }
}
