package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A half-open breaker lets one probe reach a recovering provider, and a [ProviderChain] is what
 * decides which caller that is. The breaker's own rules are in `CircuitBreakerTest`; this is the
 * wiring: one probe across both walks, how each outcome settles it, and what a refused provider
 * reads as to the caller.
 *
 * The breaker opens at t=0 on one failure with a 100ms cooldown, and the clock is then set to t=200,
 * so it is half-open before the first call.
 */
class HalfOpenProbeTest {
    private val type = EnrichmentType.ALBUM_ART
    private val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
    private val time = AtomicLong(0L)

    private fun halfOpenBreaker(): CircuitBreaker {
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = { time.get() })
        breaker.recordFailure()
        time.set(200L)
        return breaker
    }

    private fun art(provider: String) =
        EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x.com/$provider.jpg"), provider, 0.9f)

    private fun chainOf(provider: FakeProvider, breaker: CircuitBreaker) =
        ProviderChain(type, listOf(provider), mapOf(provider.id to breaker))

    /** A provider that holds every call it receives until [release] completes, then answers [answer]. */
    private class HoldingProvider(id: String, val release: CompletableDeferred<Unit>, val answer: EnrichmentResult) :
        FakeProvider(id = id) {
        val entered = AtomicInteger()

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            entered.incrementAndGet()
            release.await()
            return answer
        }
    }

    /**
     * Releases [callers] threads together at a barrier, each running [call]; a caller that comes back
     * answered is the probe, one that comes back refused is counted. Returns how many were admitted
     * into the provider while the probe was unsettled, and how many answered after it was released.
     *
     * The bound on the wait is a guard sized for a hang only: with every caller admitted, none is
     * refused, so the count never completes.
     */
    private fun admittedWhileUnsettled(
        provider: HoldingProvider,
        callers: Int,
        call: suspend () -> Boolean,
    ): Pair<Int, Int> {
        val barrier = CyclicBarrier(callers)
        val refused = CountDownLatch(callers - 1)
        val answered = CopyOnWriteArrayList<Boolean>()
        val threads = List(callers) {
            thread {
                barrier.await()
                val wasAnswered = runBlocking { call() }
                answered.add(wasAnswered)
                if (!wasAnswered) refused.countDown()
            }
        }
        try {
            assertTrue("all but one caller should be refused", refused.await(GUARD_SECONDS, TimeUnit.SECONDS))
            val admitted = provider.entered.get()
            provider.release.complete(Unit)
            threads.forEach { it.join() }
            return admitted to answered.count { it }
        } finally {
            provider.release.complete(Unit)
            threads.forEach { it.join(GUARD_SECONDS * 1000) }
        }
    }

    @Test fun `resolve admits one of sixteen concurrent callers while the probe is unsettled`() {
        // Given - a half-open breaker and a provider that holds its call until released
        val breaker = halfOpenBreaker()
        val provider = HoldingProvider("p1", CompletableDeferred(), art("p1"))
        val chain = chainOf(provider, breaker)

        // When - sixteen callers resolve together from a start barrier
        val (admitted, answered) = admittedWhileUnsettled(provider, 16) { chain.resolve(req) is EnrichmentResult.Success }

        // Then - one reached the provider before it settled, and only that caller got an answer
        assertEquals(1, admitted)
        assertEquals(1, answered)
    }

    @Test fun `resolveAll admits one of sixteen concurrent callers while the probe is unsettled`() {
        // Given - a half-open breaker and a provider that holds its call until released
        val breaker = halfOpenBreaker()
        val provider = HoldingProvider("p1", CompletableDeferred(), art("p1"))
        val chain = chainOf(provider, breaker)

        // When - sixteen callers collect together from a start barrier
        val (admitted, answered) = admittedWhileUnsettled(provider, 16) { chain.resolveAll(req).successes.isNotEmpty() }

        // Then - one reached the provider before it settled, and only that caller got an answer
        assertEquals(1, admitted)
        assertEquals(1, answered)
    }

    @Test fun `a probe that succeeds closes the breaker`() = runTest {
        // Given - a half-open breaker and a provider that answers with a Success
        val breaker = halfOpenBreaker()
        val provider = FakeProvider(id = "p1").also { it.givenResult(type, art("p1")) }

        // When - the probe resolves
        chainOf(provider, breaker).resolve(req)

        // Then - the breaker is closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a probe that finds nothing still closes the breaker`() = runTest {
        // Given - a half-open breaker and a provider that answers NotFound, which is a healthy answer
        val breaker = halfOpenBreaker()
        val provider = FakeProvider(id = "p1").also { it.givenResult(type, EnrichmentResult.NotFound(type, "p1")) }

        // When - the probe resolves
        chainOf(provider, breaker).resolve(req)

        // Then - the breaker is closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a probe that errors reopens the breaker with a cooldown from that failure`() = runTest {
        // Given - a half-open breaker and a provider that errors, called at t=250
        val breaker = halfOpenBreaker()
        val provider = FakeProvider(id = "p1").also {
            it.givenResult(type, EnrichmentResult.Error(type, "p1", "boom", null, ErrorKind.NETWORK))
        }
        time.set(250L)

        // When - the probe resolves
        chainOf(provider, breaker).resolve(req)

        // Then - the breaker stays open until 100ms after that failure, not after the first one
        time.set(349L)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        time.set(350L)
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
    }

    @Test fun `a probe that is rate limited reopens the breaker with a cooldown from that failure`() = runTest {
        // Given - a half-open breaker and a provider that answers RateLimited, called at t=250
        val breaker = halfOpenBreaker()
        val provider = FakeProvider(id = "p1").also {
            it.givenResult(type, EnrichmentResult.RateLimited(type, "p1", null))
        }
        time.set(250L)

        // When - the probe resolves
        chainOf(provider, breaker).resolve(req)

        // Then - the breaker stays open until 100ms after that failure, not after the first one
        time.set(349L)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        time.set(350L)
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
    }

    @Test fun `a provider refused because the probe is out reads as an open breaker to resolve`() = runTest {
        // Given - a half-open breaker whose probe is held by a call still in flight
        val breaker = halfOpenBreaker()
        assertNotNull(breaker.acquire())
        val provider = FakeProvider(id = "p1")

        // When - another call resolves through the chain
        val (result, execution) = chainOf(provider, breaker).resolveWithExecution(req)

        // Then - the provider was not asked, and the chain reports the outage an open breaker reports
        assertEquals(0, provider.enrichCalls.size)
        assertEquals(listOf("p1"), execution.skippedForOpenBreaker)
        assertEquals(emptyList<String>(), execution.attemptedProviderIds)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertTrue(result.message.contains("circuit-breaker cooldown"))
    }

    @Test fun `a provider refused because the probe is out reads as an open breaker to resolveAll`() = runTest {
        // Given - a half-open breaker whose probe is held by a call still in flight
        val breaker = halfOpenBreaker()
        assertNotNull(breaker.acquire())
        val provider = FakeProvider(id = "p1")

        // When - another call collects through the chain
        val (results, execution) = chainOf(provider, breaker).resolveAllWithExecution(req)

        // Then - the provider was not asked, and the chain reports the outage an open breaker reports
        assertEquals(0, provider.enrichCalls.size)
        assertEquals(listOf("p1"), execution.skippedForOpenBreaker)
        assertEquals(emptyList<String>(), execution.attemptedProviderIds)
        assertEquals(ErrorKind.NETWORK, (results.failure as EnrichmentResult.Error).errorKind)
    }

    @Test fun `resolveAll counts a provider refused at the call as skipped, not attempted`() = runTest {
        // Given - two providers sharing one half-open breaker; the first takes the probe at its call
        // and yields, so the second reaches the breaker while the probe is out, after both passed the gate
        val breaker = halfOpenBreaker()
        val p1 = object : FakeProvider(id = "p1") {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                yield()
                return art("p1")
            }
        }
        val p2 = FakeProvider(id = "p2").also { it.givenResult(type, art("p2")) }
        val chain = ProviderChain(type, listOf(p1, p2), mapOf("p1" to breaker, "p2" to breaker))

        // When - the chain collects from both
        val (results, execution) = chain.resolveAllWithExecution(req)

        // Then - one was asked and answered, and the other is reported as skipped for the breaker
        assertEquals(listOf("p1"), execution.attemptedProviderIds)
        assertEquals(listOf("p2"), execution.skippedForOpenBreaker)
        assertEquals(0, p2.enrichCalls.size)
        assertEquals(listOf("p1"), results.successes.map { it.provider })
    }

    @Test fun `a probe whose provider returns a Wikipedia biography with a thumbnail returns it unchanged`() = runTest {
        // Given - a half-open breaker and Wikipedia's Biography Success, with a thumbnail on
        // upload.wikimedia.org and no attribution anywhere on it
        val breaker = halfOpenBreaker()
        val success = EnrichmentResult.Success(
            EnrichmentType.ARTIST_BIO,
            EnrichmentData.Biography(
                text = "Radiohead are an English rock band formed in Abingdon.",
                source = "Wikipedia",
                thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Radiohead.jpg/300px-Radiohead.jpg",
            ),
            "wikipedia",
            0.95f,
        )
        val provider = FakeProvider(id = "wikipedia").also { it.givenResult(EnrichmentType.ARTIST_BIO, success) }
        val chain = ProviderChain(EnrichmentType.ARTIST_BIO, listOf(provider), mapOf("wikipedia" to breaker))

        // When - the probe resolves
        val result = chain.resolve(EnrichmentRequest.forArtist("Radiohead"))

        // Then - the Success comes back as the provider made it, and the breaker closes
        assertSame(success, result)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `a probe whose provider returns Wikipedia artwork without attribution returns it unchanged`() = runTest {
        // Given - a half-open breaker and Wikipedia's Artwork Success on upload.wikimedia.org, with
        // no attribution anywhere on it
        val breaker = halfOpenBreaker()
        val success = EnrichmentResult.Success(
            EnrichmentType.ARTIST_PHOTO,
            EnrichmentData.Artwork(
                url = "https://upload.wikimedia.org/wikipedia/commons/a/ab/Radiohead.jpg",
                thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Radiohead.jpg/300px-Radiohead.jpg",
            ),
            "wikipedia",
            0.95f,
        )
        val provider = FakeProvider(id = "wikipedia").also { it.givenResult(EnrichmentType.ARTIST_PHOTO, success) }
        val chain = ProviderChain(EnrichmentType.ARTIST_PHOTO, listOf(provider), mapOf("wikipedia" to breaker))

        // When - the probe collects
        val results = chain.resolveAll(EnrichmentRequest.forArtist("Radiohead"))

        // Then - the Success comes back as the provider made it, and the breaker closes
        assertSame(success, results.successes.single())
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `allowsRequest is false while half-open and still leaves the probe to a chain caller`() = runTest {
        // Given - a registry whose only provider has a half-open breaker
        val breaker = halfOpenBreaker()
        val provider = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(type, 100)))
            .also { it.givenResult(type, art("p1")) }
        val registry = ProviderRegistry(listOf(provider), breakerFor = { breaker })

        // When - the best-effort read is taken, then a chain caller resolves
        val readWhileHalfOpen = registry.allowsRequest("p1")
        val result = registry.chainFor(type)!!.resolve(req)

        // Then - the read said no, the chain caller still reached the provider as the probe, and
        // the success closed the breaker
        assertFalse(readWhileHalfOpen)
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(1, provider.enrichCalls.size)
        assertTrue(registry.allowsRequest("p1"))
    }

    @Test fun `allowsRequest is false while open and true while closed`() {
        // Given - registries over breakers that are open, and closed
        val open = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = { 0L }).also { it.recordFailure() }
        val closed = CircuitBreaker()
        val openRegistry = ProviderRegistry(listOf(FakeProvider(id = "p1")), breakerFor = { open })
        val closedRegistry = ProviderRegistry(listOf(FakeProvider(id = "p1")), breakerFor = { closed })

        // When - the best-effort read is taken on each
        val whileOpen = openRegistry.allowsRequest("p1")
        val whileClosed = closedRegistry.allowsRequest("p1")

        // Then - only the closed breaker admits
        assertFalse(whileOpen)
        assertTrue(whileClosed)
        assertNull(open.acquire())
    }

    private companion object {
        /** Sized so only a hang reaches it, never a slow runner. */
        const val GUARD_SECONDS = 60L
    }
}
