package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A half-open breaker lets one probe reach a recovering provider, proved with nothing between the
 * breaker and the socket: the real engine, a provider over the real [DefaultHttpClient], and a local
 * server standing in for the upstream. `HalfOpenProbeTest` pins the same rule with fakes; this
 * counts the requests that reach the server.
 *
 * One provider answers [TYPES] from one server, so both types share one breaker. The
 * breaker opens at t=0 after two failures with a 100ms cooldown; the clock is the test's own.
 */
class HalfOpenProbeEndToEndTest {
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
    private val time = AtomicLong(0L)
    private val cache = InMemoryEnrichmentCache()

    private lateinit var server: HttpServer

    /** Paths the client under test requested, in arrival order; one entry per request that reached the server. */
    private val requested = ConcurrentLinkedQueue<String>()

    /** Flipped by the test; the server answers 500 until it is set to 200. */
    @Volatile private var status = 500

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A pool, not the default single thread: were two requests to arrive together, one must not
        // wait for the other, or the count would hide the second.
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            // A loopback port is open to every process on the machine, and some probe each new one
            // (an IDE or agent host looking for a dev server). A stranger's request is answered
            // but is not counted.
            if (isFromClientUnderTest(exchange)) requested += exchange.requestURI.path.removePrefix("/")
            val body = """{"url":"https://img.example${exchange.requestURI.path}.jpg"}"""
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After fun stopServer() {
        server.stop(0)
    }

    private fun isFromClientUnderTest(exchange: HttpExchange) =
        exchange.requestHeaders.getFirst("User-Agent") == USER_AGENT

    /** Answers each type from `/<TYPE>` on the server and maps a failed call the way a real provider does. */
    private class ServerBackedProvider(private val http: HttpClient, private val baseUrl: String) : EnrichmentProvider {
        override val id = "upstream"
        override val displayName = "Upstream"
        override val capabilities = TYPES.map { ProviderCapability(it, 100) }
        override val requiresApiKey = false
        override val isAvailable = true

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult = try {
            val json = http.fetchJsonResult("$baseUrl/${type.name}").bodyOrThrowTransient()
            if (json == null) {
                EnrichmentResult.NotFound(type, id)
            } else {
                EnrichmentResult.Success(type, EnrichmentData.Artwork(json.getString("url")), id, 0.9f)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 100, clock = { time.get() })

    private fun engine(): DefaultEnrichmentEngine {
        val provider = ServerBackedProvider(DefaultHttpClient(USER_AGENT), "http://127.0.0.1:${server.address.port}")
        return DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider), breakerFor = { breaker }),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )
    }

    private fun enrichBoth(engine: DefaultEnrichmentEngine) =
        runBlocking { engine.enrich(request, TYPES).raw }

    /** Two failing calls, which is the breaker's threshold; leaves it OPEN at t=0 with the server at 500. */
    private fun openTheBreaker(engine: DefaultEnrichmentEngine) {
        repeat(2) { runBlocking { engine.enrich(request, setOf(EnrichmentType.ALBUM_ART)) } }
        check(breaker.state == CircuitBreaker.State.OPEN) { "expected OPEN, was ${breaker.state}" }
        check(requested.size == 2) { "expected two failing requests to open the breaker, saw ${requested.toList()}" }
    }

    private fun pastCooldownAndRecovered() {
        time.set(200L)
        status = 200
        check(breaker.state == CircuitBreaker.State.HALF_OPEN) { "expected HALF_OPEN, was ${breaker.state}" }
    }

    private fun siblingOf(probed: EnrichmentType) = TYPES.single { it != probed }

    @Test fun `an open breaker keeps a call off the server and reports the outage for both types`() {
        // Given - a server that returns 500, and an engine whose breaker two failing calls opened
        val engine = engine()
        openTheBreaker(engine)
        val requestsBefore = requested.size

        // When - both types are enriched while the breaker is open
        val results = enrichBoth(engine)

        // Then - the server saw nothing, and each type reads as the outage an open breaker reports
        assertEquals(requestsBefore, requested.size)
        TYPES.forEach { type ->
            val error = results[type] as EnrichmentResult.Error
            assertEquals(ErrorKind.NETWORK, error.errorKind)
            assertTrue(error.message, error.message.contains("circuit-breaker cooldown"))
        }
    }

    @Test fun `a half-open breaker sends one request to the server and refuses the sibling type`() {
        // Given - an open breaker, a cooldown that has passed, and a server that has recovered
        val engine = engine()
        openTheBreaker(engine)
        pastCooldownAndRecovered()
        val requestsBefore = requested.size

        // When - both types are enriched in one call
        val results = enrichBoth(engine)

        // Then - one request reached the server and its type succeeded, the other type got the
        // transient outage error, and the breaker closed
        val reached = requested.toList().drop(requestsBefore)
        assertEquals("a half-open breaker admits one probe, but the server saw $reached", 1, reached.size)
        val probed = EnrichmentType.valueOf(reached.single())
        assertTrue("expected Success for $probed, got ${results[probed]}", results[probed] is EnrichmentResult.Success)
        val error = results[siblingOf(probed)] as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
        assertTrue(error.message, error.message.contains("circuit-breaker cooldown"))
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test fun `the sibling type's outage error is not cached`() {
        // Given - a half-open probe that has run, so one type succeeded and the other was refused
        val engine = engine()
        openTheBreaker(engine)
        pastCooldownAndRecovered()
        val requestsBefore = requested.size
        enrichBoth(engine)
        val sibling = siblingOf(EnrichmentType.valueOf(requested.toList().drop(requestsBefore).first()))
        val key = DefaultEnrichmentEngine.entityKeyFor(request, sibling)

        // When - the cache is read for the refused type
        val positive = runBlocking { cache.get(key, sibling) }
        val negative = runBlocking { cache.getNegative(key, sibling) }

        // Then - nothing was stored for it
        assertNull(positive)
        assertNull(negative)
    }

    @Test fun `the call after the probe succeeds for both types and asks the server only for the sibling`() {
        // Given - a half-open probe that has run, so the breaker is closed and one type is cached
        val engine = engine()
        openTheBreaker(engine)
        pastCooldownAndRecovered()
        val requestsBefore = requested.size
        enrichBoth(engine)
        val probed = EnrichmentType.valueOf(requested.toList().drop(requestsBefore).first())
        val requestsAfterProbe = requested.size

        // When - both types are enriched again
        val results = enrichBoth(engine)

        // Then - both are Successes, and the only request was the one for the type the probe refused
        TYPES.forEach { type ->
            assertTrue("expected Success for $type, got ${results[type]}", results[type] is EnrichmentResult.Success)
        }
        assertEquals(listOf(siblingOf(probed).name), requested.toList().drop(requestsAfterProbe))
    }

    private companion object {
        const val USER_AGENT = "musicmeta-test"
        val TYPES = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.CD_ART)
    }
}
