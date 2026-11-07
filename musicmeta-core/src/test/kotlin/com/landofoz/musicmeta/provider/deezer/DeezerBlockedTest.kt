package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderChain
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeezerBlockedTest {
    private val http = FakeHttpClient()
    private val deezer = DeezerProvider(http, RateLimiter(0))
    private val type = EnrichmentType.TRACK_PREVIEW
    private val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")
    private val trackResponse =
        checkNotNull(javaClass.getResource("/fixtures/deezer-preview/search-track.json")).readText()
    private val fallback = FakeProvider("fallback", capabilities = listOf(ProviderCapability(type, 50)))

    @Test
    fun `an iTunes preview lookup is logged once while a cache hit is silent`() = runTest {
        // Given - Deezer has no track and iTunes can supply a cacheable preview.
        http.givenJsonResponse("search/track", """{"data":[]}""")
        val itunes = FakeProvider("itunes", capabilities = listOf(ProviderCapability(type, 40)))
        itunes.givenResult(type, EnrichmentResult.Success(
            type, EnrichmentData.TrackPreview("https://example.com/clip", "itunes"), "itunes", 0.9f,
        ))
        val lookups = mutableListOf<String>()
        val logger = object : EnrichmentLogger {
            override fun debug(tag: String, message: String) {
                if (message.startsWith("Preview lookup:")) lookups.add(message)
            }
            override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        }
        val engine = EnrichmentEngine.Builder().addProvider(deezer).addProvider(itunes)
            .cache(FakeEnrichmentCache()).logger(logger)
            .config(EnrichmentConfig(enableIdentityResolution = false)).build()

        // When - requesting the preview twice so the second request reads the cache.
        try {
            val first = engine.enrich(request, setOf(type)).raw[type]
            val second = engine.enrich(request, setOf(type)).raw[type]

            // Then - one successful lookup is logged without claiming that Deezer was blocked.
            assertTrue(first is EnrichmentResult.Success)
            assertTrue(second is EnrichmentResult.Success)
            assertEquals(1, itunes.enrichCalls.size)
            assertEquals(listOf("Preview lookup: iTunes supplied TRACK_PREVIEW."), lookups)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a Deezer block logs one warning even when the fallback succeeds`() = runTest {
        // Given - Deezer refuses this host and a later provider can supply the preview.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        fallback.givenResult(type, EnrichmentResult.Success(
            type, EnrichmentData.TrackPreview("https://example.com/clip", "fallback"), "fallback", 0.9f,
        ))
        val warnings = mutableListOf<String>()
        val logger = object : EnrichmentLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
        }
        val chain = ProviderChain(type, listOf(deezer, fallback), logger = logger)

        // When - a successful fallback hides the refusal from the returned result.
        val result = chain.resolve(request)

        // Then - exactly one warning retains the provider, HTTP status and host refusal.
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("deezer", ignoreCase = true))
        assertTrue(warnings.single().contains("403"))
        assertTrue(warnings.single().contains("blocked"))
    }

    @Test
    fun `a refused preview remains an authorization error after an empty fallback and is not cached`() = runTest {
        // Given - Deezer refuses this host with HTTP 403 and the next provider has no preview.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        val cache = FakeEnrichmentCache()
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(deezer, fallback)), cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - the same preview is requested twice without forcing a refresh.
        val first = engine.enrich(request, setOf(type)).raw[type]
        val second = engine.enrich(request, setOf(type)).raw[type]

        // Then - both calls report the block and both reach upstream instead of caching absence.
        assertEquals(ErrorKind.AUTH, (first as? EnrichmentResult.Error)?.errorKind)
        assertEquals(ErrorKind.AUTH, (second as? EnrichmentResult.Error)?.errorKind)
        assertTrue((first as EnrichmentResult.Error).message.contains("403"))
        assertTrue(first.message.contains("blocked"))
        assertEquals(2, http.requestedUrls.size)
        assertEquals(2, fallback.enrichCalls.size)
        assertTrue(cache.negativeStored.isEmpty())
        engine.close()
    }

    @Test
    fun `a refused preview falls through to a successful provider`() = runTest {
        // Given - Deezer refuses the host and the next provider has a preview.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        val expected = EnrichmentResult.Success(
            type, EnrichmentData.TrackPreview("https://example.com/clip", "fallback"), "fallback", 0.9f,
        )
        fallback.givenResult(type, expected)
        val breaker = CircuitBreaker(failureThreshold = 1)
        val chain = ProviderChain(type, listOf(deezer, fallback), mapOf(deezer.id to breaker))

        // When - resolving the preview through the chain.
        val result = chain.resolve(request)

        // Then - the fallback answers while the refusal counts as a Deezer breaker failure.
        assertEquals(expected, result)
        assertEquals(1, fallback.enrichCalls.size)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test
    fun `an empty Deezer search falls through without tripping its breaker`() = runTest {
        // Given - Deezer has no matching track and the next provider has a preview.
        http.givenJsonResponse("search/track", """{"data":[]}""")
        val expected = EnrichmentResult.Success(
            type, EnrichmentData.TrackPreview("https://example.com/clip", "fallback"), "fallback", 0.9f,
        )
        fallback.givenResult(type, expected)
        val breaker = CircuitBreaker(failureThreshold = 1)
        val chain = ProviderChain(type, listOf(deezer, fallback), mapOf(deezer.id to breaker))

        // When - resolving the preview through the chain.
        val result = chain.resolve(request)

        // Then - the fallback answers and a genuine empty search records no failure.
        assertEquals(expected, result)
        assertEquals(1, fallback.enrichCalls.size)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test
    fun `a Deezer preview prevents any fallback call`() = runTest {
        // Given - Deezer returns a matching preview.
        http.givenJsonResponse("search/track", trackResponse)
        val chain = ProviderChain(type, listOf(deezer, fallback))

        // When - resolving the preview through the chain.
        val result = chain.resolve(request)

        // Then - Deezer wins and the next provider is never called.
        assertEquals("deezer", (result as? EnrichmentResult.Success)?.provider)
        assertTrue(fallback.enrichCalls.isEmpty())
    }

    @Test
    fun `a track without a preview remains a negatively cached absence`() = runTest {
        // Given - Deezer returns a matching track without a preview URL.
        http.givenJsonResponse("search/track", org.json.JSONObject(trackResponse).also { json ->
            val rows = json.getJSONArray("data")
            repeat(rows.length()) { rows.getJSONObject(it).remove("preview") }
        }.toString())
        val cache = FakeEnrichmentCache()
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(deezer)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - resolving that preview twice.
        val first = engine.enrich(request, setOf(type)).raw[type]
        val second = engine.enrich(request, setOf(type)).raw[type]

        // Then - genuine absence remains NotFound and the second call uses the negative cache.
        assertTrue(first is EnrichmentResult.NotFound)
        assertTrue(second is EnrichmentResult.NotFound)
        assertEquals(1, http.requestedUrls.size)
        assertEquals(1, cache.negativeStored.size)
        engine.close()
    }

    @Test
    fun `preview refusals open the shared Deezer breaker for other capabilities`() = runTest {
        // Given - preview requests fail five times and artist search would otherwise answer.
        http.givenHttpResult("search/track", HttpResult.ClientError(403))
        http.givenJsonResponse("search/artist", """{"data":[]}""")
        val breaker = CircuitBreaker()
        val breakers = mapOf(deezer.id to breaker)
        val previews = ProviderChain(type, listOf(deezer), breakers)
        val photos = ProviderChain(EnrichmentType.ARTIST_PHOTO, listOf(deezer), breakers)

        // When - five refused previews are followed by a request for an artist photo.
        repeat(5) { previews.resolve(request) }
        val result = photos.resolve(EnrichmentRequest.forArtist("Radiohead"))

        // Then - the provider is in cooldown and even its other routes are not called.
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(5, http.requestedUrls.size)
        assertFalse(http.requestedUrls.any { "search/artist" in it })
    }

    @Test
    fun `similar albums also reports a refused Deezer route as an authorization error`() = runTest {
        // Given - Deezer refuses the album search with the same HTTP 403 as the track route.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        val provider = SimilarAlbumsProvider(http, RateLimiter(0))

        // When - requesting similar albums.
        val result = provider.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), EnrichmentType.SIMILAR_ALBUMS,
        )

        // Then - the block is visible under the separate similar-albums provider id.
        assertEquals(ErrorKind.AUTH, (result as? EnrichmentResult.Error)?.errorKind)
        assertEquals("deezer-similar-albums", (result as EnrichmentResult.Error).provider)
    }
}
