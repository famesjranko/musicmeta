package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class BreakerAdmissionObservationTest {
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `observational checks fail closed in half-open before resolve`() = runTest {
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

        // When - a best-effort gate observes recovery before the chain owns an actual attempt
        repeat(3) { assertFalse(breaker.allowRequest()) }
        val first = chain.resolve(request)
        val retry = chain.resolve(request)

        // Then - exactly one live call closed the breaker and the later request can run normally
        assertTrue(first is EnrichmentResult.NotFound)
        assertTrue(retry is EnrichmentResult.NotFound)
        assertEquals(2, provider.enrichCalls.size)
    }

    @Test fun `registry checks fail closed in half-open before its chain resolves`() = runTest {
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
        val registry = ProviderRegistry(listOf(provider), circuitBreakerFactory = { _ -> breaker })

        // When - the best-effort registry gate is checked before the real chain attempt
        repeat(3) { assertFalse(registry.allowsRequest(provider.id)) }
        val result = requireNotNull(registry.chainFor(EnrichmentType.ALBUM_ART)).resolve(request)

        // Then - the chain still owns and settles the one live recovery probe
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(1, provider.enrichCalls.size)
        assertTrue(registry.allowsRequest(provider.id))
    }

    @Test fun `half-open disambiguation gate makes no unowned MusicBrainz call`() = runTest {
        // Given - a merged split pair and a MusicBrainz breaker in half-open recovery
        val time = AtomicLong(0L)
        val musicBrainzBreaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 100, clock = time::get)
        musicBrainzBreaker.recordFailure()
        time.set(100L)
        val http = FakeHttpClient().apply {
            givenJsonResponse("artist?query=arid", "{\"artists\":[]}")
        }
        val contributor = object : EnrichmentProvider {
            override val id = "contributor"
            override val displayName = "Contributor"
            override val capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 1))
            override val requiresApiKey = false
            override val isAvailable = true
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType) = EnrichmentResult.Success(
                type, EnrichmentData.SimilarArtists(listOf(
                    SimilarArtist("Loathe", com.landofoz.musicmeta.EnrichmentIdentifiers(musicBrainzId = "56eb02c4-1f16-4613-8bb3-b4a752283fc3"), 1f, listOf("p"), "UK"),
                    SimilarArtist("Loathe", com.landofoz.musicmeta.EnrichmentIdentifiers(musicBrainzId = "e9ea0fbc-ccc7-4e98-9290-0a41aa848fa2"), .5f, listOf("p")),
                )), id, 1f,
            )
        }
        val musicBrainz = MusicBrainzProvider(http, RateLimiter(0L))
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(contributor, musicBrainz)) { id ->
                if (id == musicBrainz.id) musicBrainzBreaker else CircuitBreaker()
            },
            InMemoryEnrichmentCache(), EnrichmentConfig(enableIdentityResolution = false), mergers = DEFAULT_MERGERS,
        )

        // When - the engine reaches its best-effort disambiguation path with budget remaining
        engine.enrich(EnrichmentRequest.forArtist("Loathe"), setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the Boolean gate fails closed and does not make an un-tokened live request
        assertEquals(0, http.requestedUrls.count { it.contains("arid") })
    }

    @Test fun `observational checks fail closed in half-open before resolveAll`() = runTest {
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
        repeat(3) { assertFalse(breaker.allowRequest()) }
        val first = chain.resolveAll(request)
        val retry = chain.resolveAll(request)

        // Then - one live probe closes the breaker and a later merge walk is admitted
        assertTrue(first.failure == null)
        assertTrue(retry.failure == null)
        assertEquals(2, provider.enrichCalls.size)
    }
}
