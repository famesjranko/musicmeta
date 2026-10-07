package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
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
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedCacheFailureTest {
    private val type = EnrichmentType.ALBUM_ART
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `independent custom cache serves expired pins on full and partial progressive reads`() = runTest {
        // Given - an independent cache with an expired selected value and no cache-side write protection
        val cache = IndependentCache()
        val key = entityKeyFor(request, type)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED, 1)
        cache.markManuallySelected(key, type)
        cache.now = 2
        val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(type, 100)))
        provider.givenResult(type, art("fresh"))
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

        // When - full and partial cache routes emit progressive snapshots
        val full = engine.enrichProgressive(request, setOf(type)).toList()
        val partial = engine.enrichProgressive(request, setOf(type, EnrichmentType.LABEL)).toList()

        // Then - every emitted artwork is the chosen value and provider write-back cannot replace it
        for (snapshot in full + partial) {
            val value = snapshot.raw[type]
            if (value != null) assertEquals("chosen", (value as EnrichmentResult.Success).provider)
        }
        assertEquals("chosen", cache.getIncludingExpired(key, type)?.result?.provider)
        assertEquals(0, provider.enrichCalls.size)
        assertEquals(1, cache.positiveWrites)
        engine.close()
    }

    @Test fun `failed pinned positive read never permits a custom cache overwrite`() = runTest {
        // Given - a selected custom-cache entry whose positive storage is unreadable
        val cache = IndependentCache()
        val key = entityKeyFor(request, type)
        cache.put(key, type, art("chosen"), CanonicalStatus.RESOLVED, 1)
        cache.markManuallySelected(key, type)
        cache.now = 2
        cache.failPositiveRead = true
        val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(type, 100)))
        provider.givenResult(type, art("fresh"))
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

        // When - a fresh provider answer arrives despite the read failure
        val result = engine.enrich(request, setOf(type)).raw[type] as EnrichmentResult.Success

        // Then - it is served but the selected stored positive is not overwritten
        assertEquals("fresh", result.provider)
        assertEquals(1, cache.positiveWrites)
        cache.failPositiveRead = false
        assertEquals("chosen", cache.getIncludingExpired(key, type)?.result?.provider)
        engine.close()
    }

    @Test fun `cancellation during pin-state read propagates without persistence`() = runTest {
        // Given - a custom cache whose pin-state read suspends indefinitely
        val backing = IndependentCache()
        val entered = CompletableDeferred<Unit>()
        val cache = object : EnrichmentCache by backing {
            override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val persistence = CachePersistence(cache, EnrichmentConfig(), EnrichmentLogger.NoOp)
        var returnedNormally = false

        // When - the caller cancels while reading selection state
        val call = async {
            persistence.writeBack(request, request, mapOf(type to art("fresh")), context())
            returnedNormally = true
        }
        entered.await()
        call.cancelAndJoin()

        // Then - cancellation reaches the caller and no data is persisted
        assertTrue(call.isCancelled)
        assertFalse(returnedNormally)
        assertEquals(0, backing.positiveWrites)
        assertEquals(0, backing.negativeWrites)
    }

    @Test fun `unreadable pin state blocks independent custom-cache positive and negative writes`() = runTest {
        // Given - a custom cache whose selection state fails on both persistence branches
        val backing = IndependentCache()
        val cache = object : EnrichmentCache by backing {
            override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean = error("unreadable pins")
        }
        val persistence = CachePersistence(cache, EnrichmentConfig(), EnrichmentLogger.NoOp)

        // When - positive and negative provider answers reach write-back
        persistence.writeBack(request, request, mapOf(type to art("fresh")), context())
        persistence.writeBack(request, request, mapOf(type to EnrichmentResult.NotFound(type, "absent")), context())

        // Then - unreadable state is never permission to persist either answer
        assertEquals(0, backing.positiveWrites)
        assertEquals(0, backing.negativeWrites)
    }

    @Test fun `valid custom-cache marker-only miss receives its first fill until forced refresh`() = runTest {
        // Given - a selected key with no data in an independent custom cache
        val cache = IndependentCache()
        val key = entityKeyFor(request, type)
        cache.markManuallySelected(key, type)
        val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(type, 100)))
        provider.givenResult(type, art("first"))
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

        // When - enrichment fills the missing value and explicit refresh later requests a replacement
        engine.enrich(request, setOf(type))
        assertTrue(cache.isManuallySelected(key, type))
        assertEquals("first", cache.get(key, type)?.result?.provider)
        provider.givenResult(type, art("second"))
        engine.enrich(request, setOf(type), forceRefresh = true)

        // Then - the first fill preserved the marker and forced refresh cleared it for replacement
        assertFalse(cache.isManuallySelected(key, type))
        assertEquals("second", cache.get(key, type)?.result?.provider)
        engine.close()
    }

    private fun context() = WriteBackContext(
        IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.RESOLVED),
        emptySet(), emptyMap(), emptySet(), emptySet(),
    )

    @Test fun `marker-only pin bypasses a previous negative answer to obtain its first positive`() = runTest {
        // Given - a selected custom-cache key whose only stored answer is a previous absence
        val cache = IndependentCache()
        val key = entityKeyFor(request, type)
        cache.putNegative(key, type, EnrichmentResult.NotFound(type, "absent"), CanonicalStatus.RESOLVED, 1_000)
        cache.markManuallySelected(key, type)
        val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(type, 100)))
        provider.givenResult(type, art("fresh"))
        val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, EnrichmentConfig(enableIdentityResolution = false))

        // When - enrichment attempts to fill the selected key
        val result = engine.enrich(request, setOf(type)).raw[type]

        // Then - the previous absence cannot prevent the first selected positive fill
        assertTrue(result is EnrichmentResult.Success)
        assertEquals("fresh", cache.get(key, type)?.result?.provider)
        assertTrue(cache.isManuallySelected(key, type))
        engine.close()
    }

    private fun art(provider: String) = EnrichmentResult.Success(type, EnrichmentData.Artwork("https://example.test/$provider.jpg"), provider, 0.9f)

    @Test fun `a failed pin-state read fetches but does not persist`() = runTest {
        // Given - a custom cache whose manual-selection state cannot be read
        val backing = InMemoryEnrichmentCache()
        val cache = object : EnrichmentCache by backing {
            override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean =
                error("selection store unavailable")
        }
        val type = EnrichmentType.ALBUM_ART
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val provider = FakeProvider("provider", capabilities = listOf(ProviderCapability(type, 100)))
            .also {
                it.givenResult(
                    type,
                    EnrichmentResult.Success(
                        type,
                        EnrichmentData.Artwork("https://example.test/fresh.jpg"),
                        "provider",
                        0.9f,
                    ),
                )
            }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - enrichment receives a fresh provider answer
        engine.enrich(request, setOf(type))

        // Then - unreadable pin state never grants permission to write the custom cache
        assertNull(backing.get(DefaultEnrichmentEngine.entityKeyFor(request, type), type))
    }
}

/** Deliberately has no pin write guard, so engine tests cannot pass due to shipped-cache protection. */
internal class IndependentCache : EnrichmentCache {
    var now = 0L
    var positiveWrites = 0
    var negativeWrites = 0
    var failPositiveRead = false
    private val values = mutableMapOf<Pair<String, EnrichmentType>, Pair<CacheEnvelope<EnrichmentResult.Success>, Long>>()
    private val negatives = mutableMapOf<Pair<String, EnrichmentType>, CacheEnvelope<EnrichmentResult.NotFound>>()
    private val pins = mutableSetOf<Pair<String, EnrichmentType>>()

    override suspend fun get(entityKey: String, type: EnrichmentType) =
        values[entityKey to type]?.takeIf { it.second > now }?.first
    override suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? {
        check(!failPositiveRead) { "positive store unavailable" }
        return values[entityKey to type]?.first
    }
    override suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, canonicalStatus: CanonicalStatus, ttlMs: Long) {
        positiveWrites++
        values[entityKey to type] = CacheEnvelope(result, canonicalStatus) to (now + ttlMs)
    }
    override suspend fun getNegative(entityKey: String, type: EnrichmentType) = negatives[entityKey to type]
    override suspend fun putNegative(entityKey: String, type: EnrichmentType, result: EnrichmentResult.NotFound, canonicalStatus: CanonicalStatus, ttlMs: Long) {
        negativeWrites++
        negatives[entityKey to type] = CacheEnvelope(result, canonicalStatus)
    }
    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        values.keys.removeAll { it.first == entityKey && (type == null || it.second == type) }
        negatives.keys.removeAll { it.first == entityKey && (type == null || it.second == type) }
        pins.removeAll { it.first == entityKey && (type == null || it.second == type) }
    }
    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) = entityKey to type in pins
    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) { pins.add(entityKey to type) }
    override suspend fun clear() { values.clear(); negatives.clear(); pins.clear() }
}
