package com.landofoz.musicmeta.engine

import app.cash.turbine.test
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.testkit.EntityIdentity
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, config)

    @Test fun `enrichBatch emits result for each request in order`() = runTest {
        // Given - three distinct album requests and a provider that returns success for each
        val req1 = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("The Bends", "Radiohead")
        val req3 = EnrichmentRequest.forAlbum("Kid A", "Radiohead")
        val provider = FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

        // When - collecting all emissions from enrichBatch
        val emitted = mutableListOf<EnrichmentRequest>()
        engine(provider).enrichBatch(listOf(req1, req2, req3), types).test {
            // Then - three items emitted in request order, each with a success result
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
        // Given - an engine with a provider (which should never be called)
        val provider = FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        )

        // When - calling enrichBatch with an empty list
        engine(provider).enrichBatch(emptyList(), types).test {
            // Then - flow completes immediately with no items emitted
            awaitComplete()
        }
        assertEquals("provider should never be called for empty batch", 0, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch cancellation stops processing remaining requests`() = runTest {
        // Given - three requests but we only want the first result
        val req1 = EnrichmentRequest.forAlbum("Nevermind", "Nirvana")
        val req2 = EnrichmentRequest.forAlbum("In Utero", "Nirvana")
        val req3 = EnrichmentRequest.forAlbum("Bleach", "Nirvana")
        val provider = FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

        // When - taking only the first result via take(1)
        engine(provider).enrichBatch(listOf(req1, req2, req3), types).take(1).test {
            // Then - one item emitted, then flow completes (cancelled)
            val item = awaitItem()
            assertEquals(req1, item.first)
            awaitComplete()
        }

        // Then - only the first request was processed; remaining requests were not sent to the provider
        assertEquals("only first request should have been processed", 1, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch propagates forceRefresh to enrich`() = runTest {
        // Given - a request with a cached result and a provider that returns fresh data
        val req = EnrichmentRequest.forAlbum("Pablo Honey", "Radiohead")
        val cacheKey = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.put(cacheKey, EnrichmentType.ALBUM_ART, artSuccess("cached-provider"), CanonicalStatus.RESOLVED)
        val provider = FakeProvider(
            id = "fresh-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("fresh-provider")) }

        // When - calling enrichBatch with forceRefresh=true
        engine(provider).enrichBatch(listOf(req), types, forceRefresh = true).test {
            val item = awaitItem()
            // Then - cache was bypassed: provider was called and returned fresh data
            assertEquals("fresh-provider", (item.second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
            awaitComplete()
        }
        assertEquals("provider should have been called due to forceRefresh", 1, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch with STALE_IF_ERROR serves stale on provider failure`() = runTest {
        // Given - provider returns Error for all requests, but expired cache has stale data for req1
        val req1 = EnrichmentRequest.forAlbum("Amnesiac", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("Hail to the Thief", "Radiohead")
        val provider = FakeProvider(
            id = "failing",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also {
            it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "failing", "API down"))
        }
        val staleConfig = EnrichmentConfig(
            enableIdentityResolution = false,
            cacheMode = com.landofoz.musicmeta.cache.CacheMode.STALE_IF_ERROR,
        )
        val staleCache = FakeEnrichmentCache()
        val key1 = DefaultEnrichmentEngine.entityKeyFor(req1, EnrichmentType.ALBUM_ART)
        staleCache.expiredStore["$key1:${EnrichmentType.ALBUM_ART}"] = artSuccess("stale-art")
        val staleEngine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)), staleCache, staleConfig,
        )

        // When - batch enriching both requests
        staleEngine.enrichBatch(listOf(req1, req2), types).test {
            val item1 = awaitItem()
            val item2 = awaitItem()
            awaitComplete()

            // Then - req1 gets stale fallback (isStale=true), req2 gets Error (no stale available)
            val result1 = item1.second.raw[EnrichmentType.ALBUM_ART]
            assert(result1 is EnrichmentResult.Success) { "req1 should get stale Success, got $result1" }
            assert((result1 as EnrichmentResult.Success).isStale) { "req1 should be marked stale" }

            val result2 = item2.second.raw[EnrichmentType.ALBUM_ART]
            assert(result2 is EnrichmentResult.Error) { "req2 should get Error (no stale), got $result2" }
        }
    }

    @Test fun `enrichBatch returns cached results without calling provider`() = runTest {
        // Given - req1 is cached, req2 is not; provider handles req2
        val req1 = EnrichmentRequest.forAlbum("Amnesiac", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("Hail to the Thief", "Radiohead")
        val cacheKey = DefaultEnrichmentEngine.entityKeyFor(req1, EnrichmentType.ALBUM_ART)
        cache.put(cacheKey, EnrichmentType.ALBUM_ART, artSuccess("cached-provider"), CanonicalStatus.RESOLVED)
        val provider = FakeProvider(
            id = "fresh-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("fresh-provider")) }

        // When - enriching both requests
        engine(provider).enrichBatch(listOf(req1, req2), types).test {
            val item1 = awaitItem()
            val item2 = awaitItem()
            awaitComplete()

            // Then - first result comes from cache, second from provider
            assertEquals("cached-provider", (item1.second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
            assertEquals("fresh-provider", (item2.second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
        }

        // Then - provider was only called for req2 (cache hit for req1 bypassed provider)
        assertEquals("provider should only be called for uncached request", 1, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch gives each item its own ProviderCallScope, so a repeated request asks upstream again`() = runTest {
        // Given - a provider that fetches upstream once per ProviderCallScope and echoes that
        // answer to any further call within the same scope, mirroring how a real memoizing
        // provider behaves (ProviderMemoLifetimeTest), and two identical requests in one batch
        val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val upstreamCalls = mutableListOf<EnrichmentRequest>()
        val provider = object : FakeProvider(
            id = "art-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ) {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                val scope = currentCoroutineContext()[ProviderCallScope]
                if (scope != null) {
                    scope.slot(this) { upstreamCalls.add(request) }
                } else {
                    upstreamCalls.add(request)
                }
                return artSuccess(id)
            }
        }

        // When - the identical request is enriched twice in one batch. forceRefresh bypasses
        // EnrichmentCache so its own per-request memoization cannot explain a single upstream
        // fetch — the only remaining channel a shared scope could leak through is the one under
        // test
        engine(provider).enrichBatch(listOf(req, req), types, forceRefresh = true).test {
            awaitItem()
            awaitItem()
            awaitComplete()
        }

        // Then - upstream was asked once per item, not once for the whole batch: a scope shared
        // across items would let the second item's identical request be answered by the first
        // item's memo instead of asking upstream again
        assertEquals("expected one upstream fetch per batch item, not a shared scope's memo", 2, upstreamCalls.size)
    }

    @Test fun `enrichBatch attributes one item's transient failure to that item alone`() = runTest {
        // Given - one provider shared by two items in a batch: it fails transiently for the
        // first request and answers its own upstream for the second
        val req1 = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("Nevermind", "Nirvana")
        val provider = object : FakeProvider(
            id = "flaky-provider",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ) {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                return if (request == req1) {
                    EnrichmentResult.Error(type, id, "transient upstream failure")
                } else {
                    artSuccess(id)
                }
            }
        }

        // When - both requests are enriched in one batch
        val items = mutableListOf<EnrichmentResults>()
        engine(provider).enrichBatch(listOf(req1, req2), types).test {
            items.add(awaitItem().second)
            items.add(awaitItem().second)
            awaitComplete()
        }

        // Then - item 1's transient failure stays item 1's
        val result1 = items[0].raw[EnrichmentType.ALBUM_ART]
        assertTrue("req1 should get Error, got $result1", result1 is EnrichmentResult.Error)

        // And - item 2's result reflects item 2's own upstream, not item 1's failure. One
        // failure never trips the shared breaker (CircuitBreaker.DEFAULT_FAILURE_THRESHOLD is 5),
        // so item 2's own call must still answer for itself rather than being short-circuited or
        // overwritten by item 1's outcome
        val result2 = items[1].raw[EnrichmentType.ALBUM_ART]
        assertTrue("req2 should get its own Success, got $result2", result2 is EnrichmentResult.Success)
        assertEquals("flaky-provider", (result2 as EnrichmentResult.Success).provider)

        // And - the failure was attributed to exactly one upstream call: no retry doubled it,
        // and item 2 was actually asked rather than skipped
        assertEquals("expected exactly one enrich() call per item", 2, provider.enrichCalls.size)
    }

    @Test fun `enrichBatch resolves each item's own identity, not the previous item's`() = runTest {
        // Given - one provider that is both the identity provider and the ALBUM_ART provider,
        // whose identity resolution differs by request: req1 resolves confidently to its own
        // MBID, req2 stays ambiguous with a near-miss suggestion naming its own MBID
        val req1 = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("Nevermind", "Nirvana")
        val req2Suggestion = SearchCandidate(
            title = "Nevermind",
            provider = "identity-provider",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-nirvana-near-miss"),
            matchScore = 0.70f,
            artist = "Nirvana",
            year = "1991",
            releaseType = "Album",
        )
        val provider = object : FakeProvider(
            id = "identity-provider",
            isIdentityProvider = true,
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ) {
            override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult = when (request) {
                req1 -> EnrichmentResult.Success(
                    type = EnrichmentType.GENRE,
                    data = EnrichmentData.Biography(text = "resolved", source = id),
                    provider = id,
                    confidence = 0.95f,
                    resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-radiohead-ok-computer"),
                )
                else -> EnrichmentResult.NotFound(
                    type = EnrichmentType.GENRE,
                    provider = id,
                    suggestions = listOf(req2Suggestion),
                )
            }

            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                enrichCalls.add(request to type)
                return artSuccess(id)
            }
        }
        val identityEngine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - both requests are enriched in one batch
        val items = mutableListOf<Pair<EnrichmentRequest, EnrichmentResults>>()
        identityEngine.enrichBatch(listOf(req1, req2), types).test {
            items.add(awaitItem())
            items.add(awaitItem())
            awaitComplete()
        }
        val (item1Request, item1Results) = items[0]
        val (item2Request, item2Results) = items[1]

        // Then - at least one item answered with a genuine Success, so this cannot pass on a
        // batch that declines everything
        assertTrue(
            "expected item 1's ALBUM_ART to be a Success, got ${item1Results.raw[EnrichmentType.ALBUM_ART]}",
            item1Results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success,
        )

        // And - each item's own result answers the request it was asked, by the kit's rule.
        // The weak entry point applies to both: neither request carries an identifier, so both
        // resolve by name search, which never sets canonical names — a recorded gap `12` counts
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(item1Request, item1Results)
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(item2Request, item2Results)

        // And - each item's identity is its own: item 1 resolved confidently under its own MBID,
        // and item 2 stayed ambiguous under its own near-miss suggestion. The first item's
        // resolved identity reused for the second would show up here as item 2 wrongly RESOLVED
        // under item 1's MBID instead
        assertEquals(CanonicalStatus.RESOLVED, item1Results.identity.status)
        assertEquals("mbid-radiohead-ok-computer", item1Results.identity.identifiers.musicBrainzId)
        assertEquals(CanonicalStatus.AMBIGUOUS, item2Results.identity.status)
        assertEquals(
            listOf("mbid-nirvana-near-miss"),
            item2Results.identity.suggestions.map { it.identifiers.musicBrainzId },
        )
    }
}
