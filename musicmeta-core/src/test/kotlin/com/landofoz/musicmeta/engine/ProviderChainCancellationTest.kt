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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    /**
     * Cancels the job it is running under, then throws — a caller that has genuinely gone away.
     *
     * The cancel() matters: a bare `throw CancellationException()` while the job is still active is
     * not a cancelled call at all, and is correctly treated as a provider failure.
     */
    private class CancellingProvider(id: String) : FakeProvider(id = id) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            currentCoroutineContext()[Job]?.cancel()
            throw CancellationException("caller went away")
        }
    }

    /** Runs [provider] through a chain in its own scope, since it cancels the job it runs under. */
    private suspend fun runCancelledInOwnScope(
        provider: FakeProvider,
        breaker: CircuitBreaker,
        type: EnrichmentType = EnrichmentType.ALBUM_ART,
    ): CircuitBreaker.State {
        val chain = ProviderChain(type, listOf(provider), mapOf(provider.id to breaker))
        val running = CoroutineScope(Job()).async { chain.resolve(req) }
        try {
            running.await()
            fail("expected the cancellation to propagate, not become a recorded failure")
        } catch (_: CancellationException) {
            // expected
        }
        return breaker.state
    }

    @Test fun `resolve propagates cancellation and records no breaker failure`() = runTest {
        // Given the only provider is cancelled mid-call, and one failure would open its circuit
        val breaker = CircuitBreaker(failureThreshold = 1)

        // When resolving, then the provider is still healthy — cancellation said nothing about it
        assertEquals(CircuitBreaker.State.CLOSED, runCancelledInOwnScope(CancellingProvider("p1"), breaker))
        assertTrue(breaker.allowRequest())
    }

    @Test fun `resolveAll propagates cancellation and records no breaker failure`() = runTest {
        // Given the mergeable path, which fans out through async rather than iterating
        val breaker = CircuitBreaker(failureThreshold = 1)
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(CancellingProvider("p1")), mapOf("p1" to breaker))
        val running = CoroutineScope(Job()).async { chain.resolveAll(req) }

        // When collecting from every provider
        try {
            running.await()
            fail("expected the CancellationException to propagate, not become an Error result")
        } catch (_: CancellationException) {
            // expected
        }

        // Then no failure recorded against a provider that was merely cancelled
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a real cancellation routed through mapError records no breaker failure`() = runTest {
        // Given the shape every provider has — one broad catch delegating to mapError — with the
        // job genuinely cancelled first. mapError does not special-case cancellation (it cannot
        // tell ours from a provider's own withTimeout), so the Error it returns is stopped by
        // ensureActive() before the breaker sees it.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
                try {
                    currentCoroutineContext()[Job]?.cancel()
                    throw CancellationException("caller went away")
                } catch (e: Exception) {
                    mapError(type, e)
                }
        }
        val breakerState = runCancelledInOwnScope(provider, breaker)

        // Then the cancellation never counted against the provider
        assertEquals(CircuitBreaker.State.CLOSED, breakerState)
    }

    @Test fun `a foreign cancellation is still a provider failure`() = runTest {
        // Given a provider whose OWN withTimeout expired while our job is perfectly healthy. This
        // is the case a blanket `catch (CancellationException) { throw e }` got wrong: it escaped
        // the chain, cancelled sibling providers, and was reported as the engine's deadline.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
                withTimeout(1) {
                    delay(1_000)
                    EnrichmentResult.NotFound(type, id)
                }
        }
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf("p1" to breaker))

        // When resolving — the timeout belongs to the provider, not to us
        val result = chain.resolve(req)

        // Then it stays contained: an Error for that provider, and its breaker opens. It must not
        // propagate, because the engine would report it as enrichTimeoutMs expiring.
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test fun `a provider that swallows cancellation and returns an Error records no breaker failure`() = runTest {
        // Given a hostile provider — the case no rethrow of ours can reach. EnrichmentProvider is
        // public, so a consumer's implementation may catch the cancellation itself and hand back an
        // Error, which would otherwise be recorded as a failure against it.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                currentCoroutineContext()[Job]?.cancel()
                return EnrichmentResult.Error(type, id, "swallowed the cancellation", null)
            }
        }

        // Then ensureActive() stops it before the breaker is touched
        assertEquals(CircuitBreaker.State.CLOSED, runCancelledInOwnScope(provider, breaker))
    }

    @Test fun `resolveAll also guards the breaker against a swallowed cancellation`() = runTest {
        // Given the same hostile provider on the mergeable path. Without this the resolveAll guard
        // could be deleted and every other test here would stay green.
        val breaker = CircuitBreaker(failureThreshold = 1)
        val provider = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                currentCoroutineContext()[Job]?.cancel()
                return EnrichmentResult.Error(type, id, "swallowed the cancellation", null)
            }
        }
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(provider), mapOf("p1" to breaker))
        val running = CoroutineScope(Job()).async { chain.resolveAll(req) }

        // When collecting from every provider
        try {
            running.await()
            fail("expected the cancellation to propagate out of resolveAll")
        } catch (_: CancellationException) {
            // expected
        }

        // Then no failure was recorded
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
