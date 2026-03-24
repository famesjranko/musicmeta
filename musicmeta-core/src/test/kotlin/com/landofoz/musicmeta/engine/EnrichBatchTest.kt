package com.landofoz.musicmeta.engine

import app.cash.turbine.test
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class EnrichBatchTest {

    private lateinit var cache: FakeEnrichmentCache
    private val config = EnrichmentConfig(enableIdentityResolution = false)
    private val types = setOf(EnrichmentType.ALBUM_ART)

    @Before fun setup() {
        cache = FakeEnrichmentCache()
    }

    private fun artSuccess(provider: String) = EnrichmentResult.Success(
        type = EnrichmentType.ALBUM_ART,
        data = EnrichmentData.Artwork("https://example.com/art.jpg"),
        provider = provider,
        confidence = 0.9f,
    )

    private fun engine(vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, FakeHttpClient(), config)

    @Test fun `enrichBatch emits result for each request in order`() = runTest {
        // Given — three distinct album requests and a provider that returns success for each
        val req1 = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("The Bends", "Radiohead")
        val req3 = EnrichmentRequest.forAlbum("Kid A", "Radiohead")
        val provider = FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

        // When — collecting all emissions from enrichBatch
        val emitted = mutableListOf<EnrichmentRequest>()
        engine(provider).enrichBatch(listOf(req1, req2, req3), types).test {
            // Then — three items emitted in request order, each with a success result
            val item1 = awaitItem()
            assertEquals(req1, item1.first)
            assertNotNull(item1.second.raw[EnrichmentType.ALBUM_ART])

            val item2 = awaitItem()
            assertEquals(req2, item2.first)
            assertNotNull(item2.second.raw[EnrichmentType.ALBUM_ART])

            val item3 = awaitItem()
            assertEquals(req3, item3.first)
            assertNotNull(item3.second.raw[EnrichmentType.ALBUM_ART])

            awaitComplete()
        }
    }

    @Test fun `enrichBatch with empty list completes immediately`() = runTest {
        // Given — an engine with a provider (which should never be called)
        val provider = FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        )

        // When — calling enrichBatch with an empty list
        engine(provider).enrichBatch(emptyList(), types).test {
            // Then — flow completes immediately with no items emitted
            awaitComplete()
        }
        assertEquals("provider should never be called for empty batch", 0, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch cancellation stops processing remaining requests`() = runTest {
        // Given — three requests but we only want the first result
        val req1 = EnrichmentRequest.forAlbum("Nevermind", "Nirvana")
        val req2 = EnrichmentRequest.forAlbum("In Utero", "Nirvana")
        val req3 = EnrichmentRequest.forAlbum("Bleach", "Nirvana")
        val provider = FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

        // When — taking only the first result via take(1)
        engine(provider).enrichBatch(listOf(req1, req2, req3), types).take(1).test {
            // Then — one item emitted, then flow completes (cancelled)
            val item = awaitItem()
            assertEquals(req1, item.first)
            awaitComplete()
        }

        // Then — only the first request was processed; remaining requests were not sent to the provider
        assertEquals("only first request should have been processed", 1, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch propagates forceRefresh to enrich`() = runTest {
        // Given — a request with a cached result and a provider that returns fresh data
        val req = EnrichmentRequest.forAlbum("Pablo Honey", "Radiohead")
        val cacheKey = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.put(cacheKey, EnrichmentType.ALBUM_ART, artSuccess("cached-provider"))
        val provider = FakeProvider(
            id = "fresh-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("fresh-provider")) }

        // When — calling enrichBatch with forceRefresh=true
        engine(provider).enrichBatch(listOf(req), types, forceRefresh = true).test {
            val item = awaitItem()
            // Then — cache was bypassed: provider was called and returned fresh data
            assertEquals("fresh-provider", (item.second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
            awaitComplete()
        }
        assertEquals("provider should have been called due to forceRefresh", 1, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch returns cached results without calling provider`() = runTest {
        // Given — req1 is cached, req2 is not; provider handles req2
        val req1 = EnrichmentRequest.forAlbum("Amnesiac", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("Hail to the Thief", "Radiohead")
        val cacheKey = DefaultEnrichmentEngine.entityKeyFor(req1, EnrichmentType.ALBUM_ART)
        cache.put(cacheKey, EnrichmentType.ALBUM_ART, artSuccess("cached-provider"))
        val provider = FakeProvider(
            id = "fresh-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("fresh-provider")) }

        // When — enriching both requests
        engine(provider).enrichBatch(listOf(req1, req2), types).test {
            val item1 = awaitItem()
            val item2 = awaitItem()
            awaitComplete()

            // Then — first result comes from cache, second from provider
            assertEquals("cached-provider", (item1.second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
            assertEquals("fresh-provider", (item2.second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
        }

        // Then — provider was only called for req2 (cache hit for req1 bypassed provider)
        assertEquals("provider should only be called for uncached request", 1, provider.enrichCalls.size)
    }
}
