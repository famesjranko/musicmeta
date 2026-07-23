package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A cancelled call is not a provider failure. (#53)
 *
 * `CancellationException` is an `Exception`, so a broad `catch` on the provider path converts it
 * into `EnrichmentResult.Error` — and `ProviderChain` records an `Error` as a breaker *failure*.
 * Every `enrichTimeoutMs` expiry therefore used to count against providers that never failed,
 * and repeated timeouts opened the circuit against a healthy one.
 *
 * `failureThreshold = 1` throughout, so a single recorded failure is immediately visible as an
 * open circuit rather than needing several rounds to surface.
 */
class ProviderChainCancellationTest {
    private val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    private class CancellingProvider(id: String) : FakeProvider(id = id) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            throw CancellationException("caller went away")
    }

    @Test fun `resolve propagates cancellation and records no breaker failure`() = runTest {
        // Given — the only provider is cancelled mid-call, and one failure would open its circuit
        val breaker = CircuitBreaker(failureThreshold = 1)
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(CancellingProvider("p1")), mapOf("p1" to breaker))

        // When — resolving
        try {
            chain.resolve(req)
            fail("expected the CancellationException to propagate, not become an Error result")
        } catch (e: CancellationException) {
            assertEquals("caller went away", e.message)
        }

        // Then — the provider is still healthy; cancellation said nothing about it
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertTrue(breaker.allowRequest())
    }

    @Test fun `resolveAll propagates cancellation and records no breaker failure`() = runTest {
        // Given — the mergeable path, which fans out through async rather than iterating
        val breaker = CircuitBreaker(failureThreshold = 1)
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(CancellingProvider("p1")), mapOf("p1" to breaker))

        // When — collecting from every provider
        try {
            chain.resolveAll(req)
            fail("expected the CancellationException to propagate, not become an Error result")
        } catch (_: CancellationException) {
            // expected
        }

        // Then — no failure recorded against a provider that was merely cancelled
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a provider that routes cancellation through mapError records no breaker failure`() = runTest {
        // Given — the shape every real provider has: one broad catch around the whole call,
        // delegating classification to mapError. This is the path that matters, because ~35 call
        // sites look exactly like this and none of them rethrows on its own.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
                try {
                    throw CancellationException("caller went away")
                } catch (e: Exception) {
                    mapError(type, e)
                }
        }
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf("p1" to breaker))

        // When — resolving
        try {
            chain.resolve(req)
            fail("expected mapError to rethrow the CancellationException, not classify it")
        } catch (_: CancellationException) {
            // expected
        }

        // Then — the cancellation never became an Error, so the breaker never saw a failure
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a provider that swallows cancellation and returns an Error records no breaker failure`() = runTest {
        // Given a hostile provider — the case the rethrows cannot reach. EnrichmentProvider is
        // public, so a consumer's implementation may catch the cancellation itself and hand back
        // an Error, which would otherwise be recorded as a failure against it.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                val job = currentCoroutineContext()[Job]
                job?.cancel()
                return EnrichmentResult.Error(type, id, "swallowed the cancellation", null)
            }
        }
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf("p1" to breaker))

        // When — resolving in its own scope, because the provider cancels the job it runs under
        // and that must not take the test coroutine with it
        val scope = CoroutineScope(Job())
        val running = scope.async { chain.resolve(req) }
        try {
            running.await()
            fail("expected ensureActive() to reject the result of a cancelled call")
        } catch (_: CancellationException) {
            // expected
        }

        // Then — ensureActive() stopped it before the breaker was touched
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a genuine provider exception still records a breaker failure`() = runTest {
        // Given — the same shape, but a real failure rather than a cancellation. This is the
        // control: the rethrow must not have made ProviderChain blind to actual errors.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val failing = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
                error("provider blew up")
        }
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(failing), mapOf("p1" to breaker))

        // When — resolving
        val result = chain.resolve(req)

        // Then — reported as an Error, and the circuit opens
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }
}
