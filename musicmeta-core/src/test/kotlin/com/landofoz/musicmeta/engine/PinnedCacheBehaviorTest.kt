package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedCacheBehaviorTest {
    private val type = EnrichmentType.ALBUM_ART
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `invalidate and clear release selected values for ordinary fetching`() = runTest {
        // Given - a manually selected value and an engine with a replacement provider
        val cache = IndependentCache()
        val key = entityKeyFor(request, type)
        val provider = provider("fresh")
        val engine = engine(cache, provider)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(key, type)

        // When - explicit invalidation and then cache clear each end a selection lifetime
        engine.invalidate(request, type)
        val afterInvalidate = engine.enrich(request, setOf(type)).raw[type] as EnrichmentResult.Success
        cache.markManuallySelected(key, type)
        cache.clear()
        val afterClear = engine.enrich(request, setOf(type)).raw[type] as EnrichmentResult.Success

        // Then - both operations permit fresh fetching and leave no selection marker
        assertEquals("fresh", afterInvalidate.provider)
        assertEquals("fresh", afterClear.provider)
        assertFalse(cache.isManuallySelected(key, type))
        assertEquals(2, provider.enrichCalls.size)
        engine.close()
    }

    @Test fun `requested key and proven canonical alias enforce their pins independently`() = runTest {
        // Given - an identifier-only request with a distinct canonical name alias
        val exact = EnrichmentRequest.forAlbumByMbid("release-id")
        val named = request
        val exactKey = entityKeyFor(exact, type)
        val aliasKey = entityKeyForName(named, type)
        val cache = IndependentCache()
        val persistence = CachePersistence(cache, EnrichmentConfig(), EnrichmentLogger.NoOp)
        val context = WriteBackContext(
            IdentityResolution(EnrichmentIdentifiers(musicBrainzId = "release-id"), CanonicalStatus.RESOLVED),
            emptySet(), emptyMap(), emptySet(), emptySet(),
        )
        cache.put(aliasKey, type, art("alias-choice"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(aliasKey, type)

        // When - positive and negative writes address both keys, followed by an exact-key pin
        persistence.writeBack(exact, named, mapOf(type to art("fresh")), context)
        persistence.writeBack(exact, named, mapOf(type to EnrichmentResult.NotFound(type, "absent")), context)
        assertEquals("fresh", cache.getIncludingExpired(exactKey, type)?.result?.provider)
        assertEquals("alias-choice", cache.getIncludingExpired(aliasKey, type)?.result?.provider)
        assertNull(cache.getNegative(aliasKey, type))
        cache.invalidate(aliasKey, type)
        cache.markManuallySelected(exactKey, type)
        persistence.writeBack(exact, named, mapOf(type to art("alias-fresh")), context)

        // Then - the pinned primary stays selected while its unpinned alias accepts fresh data
        assertEquals("fresh", cache.getIncludingExpired(exactKey, type)?.result?.provider)
        assertEquals("alias-fresh", cache.getIncludingExpired(aliasKey, type)?.result?.provider)
    }

    @Test fun `force refresh and invalidate clear a proven canonical alias even when fetching fails`() = runTest {
        // Given - an identifier-only request whose identity provider supplies canonical names
        val exact = EnrichmentRequest.forAlbumByMbid("release-id")
        val cache = IndependentCache()
        val aliasKey = entityKeyForName(request, type)
        val identity = object : FakeProvider("identity", isIdentityProvider = true) {
            override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult {
                currentCoroutineContext()[ResolvedEntityNames]?.offer("OK Computer", "Radiohead")
                return EnrichmentResult.Success(
                    EnrichmentType.GENRE, EnrichmentData.Metadata(), id, 1f,
                    resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "release-id"),
                )
            }
        }
        val provider = provider("fresh")
        provider.givenResult(type, EnrichmentResult.Error(type, "fresh", "offline"))
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(identity, provider)), cache, EnrichmentConfig())
        cache.put(aliasKey, type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(aliasKey, type)

        // When - a forced call fails and a later explicit invalidation resolves the same alias
        engine.enrich(exact, setOf(type), forceRefresh = true)
        assertFalse(cache.isManuallySelected(aliasKey, type))
        cache.put(aliasKey, type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(aliasKey, type)
        engine.invalidate(exact, type)

        // Then - both explicit overrides remove the proven canonical alias and its selection
        assertFalse(cache.isManuallySelected(aliasKey, type))
        assertNull(cache.getIncludingExpired(aliasKey, type))
        engine.close()
    }

    @Test fun `legacy Wikipedia biography refetch heals to absence on full and partial routes`() = runTest {
        // Given - legacy biography entries lacking article credit and a provider repeating that payload
        val bioType = EnrichmentType.ARTIST_BIO
        val artist = EnrichmentRequest.forArtist("Radiohead")
        val legacy = EnrichmentResult.Success(bioType, EnrichmentData.Biography("Biography", "Wikipedia"), "wikipedia", 1f)
        for (types in listOf(setOf(bioType), setOf(bioType, EnrichmentType.LABEL))) {
            val cache = IndependentCache()
            cache.put(entityKeyFor(artist, bioType), bioType, legacy, CanonicalStatus.RESOLVED)
            val provider = FakeProvider("wikipedia", capabilities = listOf(ProviderCapability(bioType, 100)))
            provider.givenResult(bioType, legacy)
            val engine = engine(cache, provider)

            // When - two successive calls encounter the incomplete legacy answer
            val first = engine.enrichProgressive(artist, types).toList().last()
            val second = engine.enrichProgressive(artist, types).toList().last()

            // Then - one refetch heals to cached absence rather than repeated rejected Success
            assertTrue(first.raw[bioType] is EnrichmentResult.NotFound)
            assertTrue(second.raw[bioType] is EnrichmentResult.NotFound)
            assertEquals(1, provider.enrichCalls.size)
            engine.close()
        }
    }

    @Test fun `unsafe legacy Wikipedia stale values cannot replace a provider error`() = runTest {
        // Given - expired legacy biography and artwork entries without attribution
        val artist = EnrichmentRequest.forArtist("Radiohead")
        val legacy = listOf(
            art("wikipedia") to request,
            EnrichmentResult.Success(EnrichmentType.ARTIST_BIO, EnrichmentData.Biography("Biography", "Wikipedia"), "wikipedia", 1f) to artist,
        )
        for ((value, req) in legacy) {
            val cache = IndependentCache()
            cache.put(entityKeyFor(req, value.type), value.type, value, CanonicalStatus.RESOLVED, 1)
            cache.now = 2
            val persistence = CachePersistence(cache, EnrichmentConfig(cacheMode = CacheMode.STALE_IF_ERROR), EnrichmentLogger.NoOp)
            val error = EnrichmentResult.Error(value.type, "provider", "offline")

            // When - stale fallback inspects the unsafe expired payload
            val served = persistence.applyStaleCacheToType(req, value.type, error)

            // Then - absence of attribution prevents stale substitution
            assertEquals(error, served)
        }
    }

    @Test fun `safe artwork alternatives survive full partial expired and pinned legacy reads`() = runTest {
        // Given - an unsafe Wikipedia primary with safe and unsafe alternatives
        val legacy = art("wikipedia").copy(data = EnrichmentData.Artwork(
            "https://example.test/unsafe.jpg",
            alternatives = listOf(ArtworkSource("safe", "https://example.test/safe.jpg"), ArtworkSource("wikipedia", "https://example.test/unsafe-alt.jpg")),
        ))
        for (route in listOf("full", "partial", "stale", "pinned")) {
            val cache = IndependentCache()
            val key = entityKeyFor(request, type)
            cache.put(key, type, legacy, CanonicalStatus.RESOLVED, 1)
            if (route == "stale" || route == "pinned") cache.now = 2
            if (route == "pinned") cache.markManuallySelected(key, type)
            val provider = provider("fresh")
            provider.givenResult(type, EnrichmentResult.Error(type, "fresh", "offline"))
            val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR))

            // When - each cache route serves the surviving candidate
            val types = if (route == "partial") setOf(type, EnrichmentType.LABEL) else setOf(type)
            val served = engine.enrichProgressive(request, types).toList().last().raw[type] as EnrichmentResult.Success

            // Then - safe artwork is promoted and unsafe alternatives are removed without clearing pins
            assertEquals("safe", served.provider)
            assertEquals("https://example.test/safe.jpg", (served.data as EnrichmentData.Artwork).url)
            assertNull((served.data as EnrichmentData.Artwork).alternatives)
            assertEquals(route == "pinned", cache.isManuallySelected(key, type))
            engine.close()
        }
    }

    @Test fun `article credit never authorizes a legacy Wikipedia biography thumbnail`() = runTest {
        // Given - article-attributed Wikipedia text still carrying an uncredited file thumbnail
        val biography = EnrichmentResult.Success(
            EnrichmentType.ARTIST_BIO,
            EnrichmentData.Biography("Biography", "Wikipedia", thumbnailUrl = "https://example.test/unsafe.jpg", attribution = ContentAttribution("Radiohead", "https://en.wikipedia.org/wiki/Radiohead")),
            "wikipedia", 1f,
        )
        val artist = EnrichmentRequest.forArtist("Radiohead")
        for (route in listOf("full", "partial", "stale", "pinned")) {
            val cache = IndependentCache()
            val key = entityKeyFor(artist, biography.type)
            cache.put(key, biography.type, biography, CanonicalStatus.RESOLVED, 1)
            if (route == "stale" || route == "pinned") cache.now = 2
            if (route == "pinned") cache.markManuallySelected(key, biography.type)
            val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(biography.type, 100)))
            provider.givenResult(biography.type, EnrichmentResult.Error(biography.type, "fresh", "offline"))
            val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR))

            // When - each cache route serves the attributed article
            val types = if (route == "partial") setOf(biography.type, EnrichmentType.LABEL) else setOf(biography.type)
            val served = engine.enrichProgressive(artist, types).toList().last().raw[biography.type] as EnrichmentResult.Success

            // Then - eligible text and article credit survive but the unsafe image is withheld
            assertEquals("Biography", (served.data as EnrichmentData.Biography).text)
            assertNull((served.data as EnrichmentData.Biography).thumbnailUrl)
            assertEquals((biography.data as EnrichmentData.Biography).attribution, (served.data as EnrichmentData.Biography).attribution)
            assertEquals(route == "pinned", cache.isManuallySelected(key, biography.type))
            engine.close()
        }
    }

    @Test fun `legacy pinned biography is withheld after expiry without erasing its choice`() = runTest {
        // Given - an expired pinned Wikipedia biography without article credit
        val bioType = EnrichmentType.ARTIST_BIO
        val artist = EnrichmentRequest.forArtist("Radiohead")
        val cache = IndependentCache()
        val key = entityKeyFor(artist, bioType)
        val legacy = EnrichmentResult.Success(bioType, EnrichmentData.Biography("Biography", "Wikipedia"), "wikipedia", 1f)
        cache.put(key, bioType, legacy, CanonicalStatus.RESOLVED, 1)
        cache.markManuallySelected(key, bioType)
        cache.now = 2
        val provider = FakeProvider("wikipedia", capabilities = listOf(ProviderCapability(bioType, 100)))
        provider.givenResult(bioType, legacy)
        val engine = engine(cache, provider)

        // When - the progressive partial route encounters the unsafe selected value
        val served = engine.enrichProgressive(artist, setOf(bioType, EnrichmentType.LABEL)).toList().last()

        // Then - biography is withheld and neither positive nor negative writes alter the selection
        assertTrue(served.raw[bioType] is EnrichmentResult.NotFound)
        assertTrue(cache.isManuallySelected(key, bioType))
        assertEquals(legacy, cache.getIncludingExpired(key, bioType)?.result)
        assertNull(cache.getNegative(key, bioType))
        engine.close()
    }

    @Test fun `an expired pinned result is served without a provider call`() = runTest {
        // Given - an expired manually selected value in the default cache
        var now = 1_000L
        val cache = InMemoryEnrichmentCache(clock = { now })
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED, ttlMs = 1)
        cache.markManuallySelected(key, type)
        now += 2
        val provider = provider("fresh")
        val engine = engine(cache, provider)

        // When - enriching after its ordinary TTL elapsed
        val result = engine.enrich(request, setOf(type)).raw.getValue(type) as EnrichmentResult.Success

        // Then - the pinned selection remains the served result and no refresh occurs
        assertEquals("chosen", result.provider)
        assertEquals(0, provider.enrichCalls.size)
    }

    @Test fun `force refresh removes a pin before fetching and writing fresh data`() = runTest {
        // Given - a pinned cached choice and a provider with a replacement
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(key, type)
        val provider = provider("fresh")
        val engine = engine(cache, provider)

        // When - forceRefresh requests a replacement
        val result = engine.enrich(request, setOf(type), forceRefresh = true).raw.getValue(type) as EnrichmentResult.Success

        // Then - explicit refresh clears the pin and the replacement persists
        assertEquals("fresh", result.provider)
        assertFalse(cache.isManuallySelected(key, type))
        assertEquals("fresh", cache.get(key, type)?.result?.provider)
    }

    @Test fun `a marker-only pin allows its first positive fill`() = runTest {
        // Given - a selected key with no stored positive data
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.markManuallySelected(key, type)
        val provider = provider("fresh")

        // When - enrichment obtains its first positive answer
        engine(cache, provider).enrich(request, setOf(type))

        // Then - the first value is stored while the pin remains present
        assertEquals("fresh", cache.get(key, type)?.result?.provider)
        assertEquals(true, cache.isManuallySelected(key, type))
    }

    @Test fun `a pinned key rejects both positive and negative direct write back`() = runTest {
        // Given - a pinned positive cache entry
        val cache = InMemoryEnrichmentCache()
        cache.put("key", type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected("key", type)

        // When - positive then negative automatic writes race the selected value
        cache.put("key", type, art("replacement"), CanonicalStatus.RESOLVED)
        cache.putNegative("key", type, EnrichmentResult.NotFound(type, "provider"), CanonicalStatus.RESOLVED, 1_000)

        // Then - neither write replaces or shadows the selected positive value
        assertEquals("chosen", cache.get("key", type)?.result?.provider)
        assertNull(cache.getNegative("key", type))
    }

    @Test fun `unsafe pinned Wikipedia artwork is withheld without clearing its pin`() = runTest {
        // Given - a pinned legacy Wikipedia image with no file attribution
        val cache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        cache.put(key, type, art("wikipedia"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(key, type)
        val provider = provider("fresh")

        // When - enrichment reads the unsafe pinned entry
        val result = engine(cache, provider).enrich(request, setOf(type)).raw.getValue(type) as EnrichmentResult.Success

        // Then - it is withheld, fresh data is served, and explicit invalidation is still required to clear the pin
        assertEquals("fresh", result.provider)
        assertEquals(true, cache.isManuallySelected(key, type))
        assertEquals("wikipedia", cache.getIncludingExpired(key, type)?.result?.provider)
    }

    private fun engine(cache: EnrichmentCache, provider: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

    private fun provider(name: String) = FakeProvider(
        id = name,
        capabilities = listOf(ProviderCapability(type, 100)),
    ).also { it.givenResult(type, art(name)) }

    private fun art(provider: String) = EnrichmentResult.Success(
        type,
        EnrichmentData.Artwork("https://example.test/$provider.jpg"),
        provider,
        0.9f,
    )
}
