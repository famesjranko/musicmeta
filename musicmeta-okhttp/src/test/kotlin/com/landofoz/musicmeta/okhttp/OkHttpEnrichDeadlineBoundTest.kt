package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The published invariant end to end through the OkHttp adapter: with retries in play against an
 * upstream that accepts and never answers, `enrich()` returns within `enrichTimeoutMs` plus slack,
 * and a field that settled before the deadline keeps its result. The core client's twin is
 * `EnrichDeadlineBoundTest` in `musicmeta-core`.
 *
 * `runBlocking`, never `runTest`: the engine's timeout on a virtual clock fires unconditionally
 * over real I/O and the bound becomes a tautology. Slack is generous for CI noise — the red
 * baseline took the transport's full 5 s read timeout, so the boundary is not a close call.
 */
class OkHttpEnrichDeadlineBoundTest {

    private val server = MockWebServer()

    @After fun tearDown() { server.shutdown() }

    private fun stuckProvider(client: OkHttpEnrichmentClient): EnrichmentProvider =
        object : EnrichmentProvider {
            override val id = "stuck"
            override val displayName = "Stuck"
            override val capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100))
            override val requiresApiKey = false
            override val isAvailable = true
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                client.fetchJsonResult(server.url("/hang").toString())
                return EnrichmentResult.NotFound(type, id)
            }
        }

    private fun healthyProvider(): EnrichmentProvider = object : EnrichmentProvider {
        override val id = "healthy"
        override val displayName = "Healthy"
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))
        override val requiresApiKey = false
        override val isAvailable = true
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.Success(
                EnrichmentType.GENRE,
                EnrichmentData.Metadata(genres = listOf("rock")),
                provider = id,
                confidence = 0.9f,
            )
    }

    @Test fun `one stuck upstream cannot push enrich past its deadline, and settled fields survive`() {
        // Given - an upstream that accepts and never answers, behind a client whose own read
        // timeout and retries dwarf the deadline, beside a provider that answers instantly
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val client = OkHttpEnrichmentClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            "TestAgent/1.0",
        )
        val engine = EnrichmentEngine.Builder()
            .addProvider(stuckProvider(client))
            .addProvider(healthyProvider())
            .config(EnrichmentConfig(enrichTimeoutMs = 1_200, enableIdentityResolution = false))
            .build()

        // When - both types are enriched, against real elapsed time
        val startNanos = System.nanoTime()
        val results = runBlocking {
            engine.enrich(
                EnrichmentRequest.forArtist("Radiohead"),
                setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO),
            )
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the call held its deadline instead of the transport's 5 s, the settled field kept
        // its result, and the stuck one reports the deadline honestly
        assertTrue("took ${wallMs}ms against a 1200ms deadline", wallMs < 4_500)
        val genre = results.raw[EnrichmentType.GENRE]
        assertTrue("expected Success for the settled field, got $genre", genre is EnrichmentResult.Success)
        val bio = results.raw[EnrichmentType.ARTIST_BIO]
        assertTrue("expected Error for the stuck field, got $bio", bio is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (bio as EnrichmentResult.Error).errorKind)
    }
}
