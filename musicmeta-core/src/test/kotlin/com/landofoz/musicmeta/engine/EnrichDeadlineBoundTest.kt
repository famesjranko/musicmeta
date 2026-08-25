package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The published invariant, end to end: with retries and a redirect in play against an upstream
 * that accepts and never answers, `enrich()` returns within `enrichTimeoutMs` plus slack, and a
 * field that settled before the deadline keeps its result.
 *
 * `runBlocking`, never `runTest`: the engine's `withTimeoutOrNull` on a virtual clock fires
 * unconditionally over real I/O and the bound becomes a tautology (`docs/pitfalls.md` §17). The
 * slack is generous because CI wall clocks are noisy — the red baseline this test was watched
 * fail against took the transport's full 8 s, so the boundary it pins is not a close call.
 */
class EnrichDeadlineBoundTest {

    private lateinit var server: HttpServer
    private val released = CountDownLatch(1)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/redirect") { exchange ->
            runCatching {
                exchange.responseHeaders.add("Location", "/hang")
                exchange.sendResponseHeaders(307, -1)
            }
        }
        server.createContext("/hang") { exchange ->
            released.await(15, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, -1) }
        }
        server.start()
    }

    @After fun stopServer() {
        released.countDown()
        server.stop(0)
    }

    @Test fun `one stuck upstream cannot push enrich past its deadline, and settled fields survive`() {
        // Given - a provider whose fetch redirects into a hang, behind a client whose own timeout
        // and retries dwarf the deadline, beside a provider that answers instantly
        val httpClient = DefaultHttpClient("musicmeta-test", timeoutMs = 8_000, maxRetries = 3)
        val hangUrl = "http://127.0.0.1:${server.address.port}/redirect"
        val stuck = object : FakeProvider(
            id = "stuck",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
        ) {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                httpClient.fetchJsonResult(hangUrl)
                return EnrichmentResult.NotFound(type, id)
            }
        }
        val healthy = FakeProvider(
            id = "healthy",
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.GENRE,
                EnrichmentResult.Success(
                    EnrichmentType.GENRE,
                    EnrichmentData.Metadata(genres = listOf("rock")),
                    provider = "healthy",
                    confidence = 0.9f,
                ),
            )
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(stuck, healthy)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enrichTimeoutMs = 1_200, enableIdentityResolution = false),
            mergers = emptyList(),
        )

        // When - both types are enriched, against real elapsed time
        val startNanos = System.nanoTime()
        val results = runBlocking {
            engine.enrich(
                EnrichmentRequest.forArtist("Radiohead"),
                setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO),
            )
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the call held its deadline instead of the transport's 8 s, the settled field kept
        // its result, and the stuck one reports the deadline honestly
        assertTrue("took ${wallMs}ms against a 1200ms deadline", wallMs < 5_000)
        val genre = results.raw[EnrichmentType.GENRE]
        assertTrue("expected Success for the settled field, got $genre", genre is EnrichmentResult.Success)
        val bio = results.raw[EnrichmentType.ARTIST_BIO]
        assertTrue("expected Error for the stuck field, got $bio", bio is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (bio as EnrichmentResult.Error).errorKind)
    }
}
