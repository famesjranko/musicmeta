package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 429 is the one transient failure a consumer can act on differently, so it reaches them as
 * [EnrichmentResult.RateLimited] rather than an `Error` indistinguishable from a 5xx.
 *
 * The second test is the load-bearing one. The chain's `RateLimited` arms once recorded neither
 * breaker success nor failure, so wiring the variant up without touching them would have switched
 * the breaker *off* for throttled providers — the exact collapse `bodyOrThrowTransient` throws to
 * avoid (`docs/pitfalls.md` §4), and one no other test notices.
 */
class RateLimitedWiringTest {

    private val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

    /** A real provider whose whole host answers every request with a 429. */
    private fun rateLimitedProvider(): LrcLibProvider {
        val http = FakeHttpClient()
        http.givenHttpResult("lrclib.net", HttpResult.RateLimited(retryAfterMs = 1000))
        http.givenHttpResultArray("lrclib.net", HttpResult.RateLimited(retryAfterMs = 1000))
        http.givenRedirectResult("lrclib.net", HttpResult.RateLimited(retryAfterMs = 1000))
        return LrcLibProvider(http, RateLimiter(0))
    }

    @Test
    fun `a 429 reaches the consumer as RateLimited carrying Retry-After`() = runTest {
        // Given - a chain over a provider whose host is throttling every call
        val chain = ProviderChain(EnrichmentType.LYRICS_PLAIN, listOf(rateLimitedProvider()))

        // When - the chain resolves the type
        val result = chain.resolve(request)

        // Then - RateLimited with the upstream's Retry-After, not Error carrying NETWORK
        assertTrue("expected RateLimited, got $result", result is EnrichmentResult.RateLimited)
        assertEquals(1000L, (result as EnrichmentResult.RateLimited).retryAfterMs)
    }

    @Test
    fun `a persistently rate-limited provider trips its circuit breaker`() = runTest {
        // Given - one throttled provider behind the registry's own breaker for the type
        val chain = ProviderRegistry(listOf(rateLimitedProvider()))
            .chainFor(EnrichmentType.LYRICS_PLAIN)!!

        // When - it is asked ten times, twice the breaker's five-failure threshold
        repeat(TRIP_ATTEMPTS) { chain.resolve(request) }
        val afterTripping = chain.resolve(request)

        // Then - the chain reports the breaker outage, so nobody was asked an eleventh time
        assertTrue(
            "expected the breaker-outage Error, got $afterTripping",
            afterTripping is EnrichmentResult.Error,
        )
        assertTrue(
            "expected a cooldown message, got ${(afterTripping as EnrichmentResult.Error).message}",
            afterTripping.message.contains("circuit-breaker cooldown"),
        )
    }

    @Test
    fun `a mergeable type reports the throttle and records the failure too`() = runTest {
        // Given - the same throttled provider on the fan-out path resolveAll takes
        val chain = ProviderRegistry(listOf(rateLimitedProvider()))
            .chainFor(EnrichmentType.LYRICS_PLAIN)!!

        // When - every provider is asked concurrently, repeatedly
        repeat(TRIP_ATTEMPTS) { chain.resolveAll(request) }
        val afterTripping = chain.resolveAll(request)

        // Then - no successes, and the breaker opened rather than treating a 429 as a no-op
        assertEquals(emptyList<EnrichmentResult.Success>(), afterTripping.successes)
        assertTrue(
            "expected the breaker-outage Error, got ${afterTripping.failure}",
            (afterTripping.failure as? EnrichmentResult.Error)
                ?.message?.contains("circuit-breaker cooldown") == true,
        )
    }

    private companion object {
        /** Twice `CircuitBreaker`'s five consecutive failures, so the trip is not a boundary case. */
        const val TRIP_ATTEMPTS = 10
    }
}
