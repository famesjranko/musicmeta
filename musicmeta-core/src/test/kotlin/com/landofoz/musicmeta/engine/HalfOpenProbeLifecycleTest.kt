package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class HalfOpenProbeLifecycleTest {
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    private fun halfOpen(): CircuitBreaker {
        val time = AtomicLong(0L)
        return CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get).also {
            it.recordFailure()
            time.set(100L)
        }
    }

    @Test fun `resolve admits one in-flight half-open probe`() = runTest {
        // Given - a half-open provider whose recovery probe has not completed
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                entered.complete(Unit)
                release.await()
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val breaker = halfOpen()
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf(provider.id to breaker))

        // When - a second resolve arrives while the first owns the probe
        val first = async { chain.resolve(request) }
        entered.await()
        val second = async { chain.resolve(request) }
        runCurrent()
        release.complete(Unit)
        first.await()

        // Then - the second caller is refused without starting another provider call
        assertTrue(second.await() is EnrichmentResult.Error)
        assertEquals(1, provider.enrichCalls.size)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `resolveAll admits one in-flight half-open probe`() = runTest {
        // Given - a half-open merge provider whose recovery probe has not completed
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                entered.complete(Unit)
                release.await()
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(provider), mapOf(provider.id to halfOpen()))

        // When - a second merge walk arrives while the first owns the probe
        val first = async { chain.resolveAll(request) }
        entered.await()
        val second = async { chain.resolveAll(request) }
        runCurrent()
        release.complete(Unit)
        first.await()

        // Then - the second walk reports the breaker outage without another attempt
        assertTrue(second.await().failure is EnrichmentResult.Error)
        assertEquals(1, provider.enrichCalls.size)
    }

    @Test fun `caller cancellation abandons a half-open probe for immediate retry`() = runTest {
        // Given - a half-open provider whose admitted caller is waiting indefinitely
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    never.await()
                }
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val breaker = halfOpen()
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf(provider.id to breaker))

        // When - the admitted caller is cancelled before its provider returns
        val cancelled = async { chain.resolve(request) }
        entered.await()
        cancelled.cancelAndJoin()
        val retry = chain.resolve(request)

        // Then - cancellation did not count as failure and a new probe can start immediately
        assertTrue(retry is EnrichmentResult.NotFound)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(2, provider.enrichCalls.size)
    }
}
