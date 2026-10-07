package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinnedCacheBehaviorTest {
    private lateinit var database: EnrichmentCacheDatabase
    private lateinit var cache: RoomEnrichmentCache
    private val type = EnrichmentType.ALBUM_ART

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomEnrichmentCache(database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao())
    }

    @After fun tearDown() = database.close()

    @Test fun `Room-backed engine serves expired pins through cleanup and progressive routes`() = runTest {
        // Given - a selected engine result with an expired Room row
        var now = 1_000L
        cache = RoomEnrichmentCache(database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao(), { now })
        var calls = 0
        var answer = art("chosen")
        val provider = object : EnrichmentProvider {
            override val id = "source"
            override val displayName = "Source"
            override val capabilities = listOf(ProviderCapability(type, 100))
            override val requiresApiKey = false
            override val isAvailable = true
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                calls++
                return answer
            }
        }
        val engine = EnrichmentEngine.Builder().addProvider(provider).cache(cache)
            .config(EnrichmentConfig(enableIdentityResolution = false, ttlOverrides = mapOf(type to 1L))).build()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        engine.enrich(request, setOf(type))
        engine.markManuallySelected(request, type)
        answer = art("fresh")
        now += 2

        // When - cleanup runs before fully and partly cached progressive requests
        cache.deleteExpired()
        val full = engine.enrichProgressive(request, setOf(type)).toList().last()
        val partial = engine.enrichProgressive(request, setOf(type, EnrichmentType.LABEL)).toList().last()

        // Then - both routes serve the chosen value without a provider refetch
        assertEquals("chosen", (full.raw[type] as EnrichmentResult.Success).provider)
        assertEquals("chosen", (partial.raw[type] as EnrichmentResult.Success).provider)
        assertEquals(1, calls)
        assertEquals(true, engine.isManuallySelected(request, type))
        engine.close()
    }

    @Test fun `Room expiry cleanup retains selected positives until explicit removal`() = runTest {
        // Given - expired selected and ordinary entries
        var now = 1_000L
        cache = RoomEnrichmentCache(database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao(), { now })
        cache.put("selected", type, art("chosen"), CanonicalStatus.RESOLVED, 1)
        cache.put("ordinary", type, art("ordinary"), CanonicalStatus.RESOLVED, 1)
        cache.markManuallySelected("selected", type)
        now += 2

        // When - housekeeping removes expired data
        cache.deleteExpired()

        // Then - the selected value and marker survive while ordinary expired data is removed
        assertEquals("chosen", cache.getIncludingExpired("selected", type)?.result?.provider)
        assertEquals(true, cache.isManuallySelected("selected", type))
        assertNull(cache.getIncludingExpired("ordinary", type))
    }

    @Test fun `Room write queued before selection cannot overwrite after selection commits`() = runTest {
        // Given - a positive row and a write paused immediately before the atomic SQL statement
        cache.put("key", type, art("chosen"), CanonicalStatus.RESOLVED)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val dao = object : EnrichmentCacheDao by database.enrichmentCacheDao() {
            override suspend fun insertUnlessPinned(
                entityKey: String, type: String, provider: String, dataJson: String, confidence: Float,
                lookupProvenance: String?, canonicalStatus: String, resolvedIdsJson: String?,
                cachedAt: Long, expiresAt: Long, schemaVersion: Int,
            ) {
                entered.complete(Unit)
                resume.await()
                database.enrichmentCacheDao().insertUnlessPinned(
                    entityKey, type, provider, dataJson, confidence, lookupProvenance, canonicalStatus,
                    resolvedIdsJson, cachedAt, expiresAt, schemaVersion,
                )
            }
        }
        val writer = RoomEnrichmentCache(dao, database.negativeCacheDao(), database.selectionDao())

        // When - selection commits while the replacement write is suspended
        val job = launch { writer.put("key", type, art("replacement"), CanonicalStatus.RESOLVED) }
        entered.await()
        cache.markManuallySelected("key", type)
        resume.complete(Unit)
        job.join()

        // Then - SQL checks the committed selection and preserves the chosen row
        assertEquals("chosen", cache.get("key", type)?.result?.provider)
    }

    @Test fun `Room invalidate and clear end the pin lifetime`() = runTest {
        // Given - selected positives at two distinct keys
        cache.put("first", type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.put("second", type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected("first", type)
        cache.markManuallySelected("second", type)

        // When - one key is invalidated and the remaining cache is cleared
        cache.invalidate("first", type)
        assertEquals(false, cache.isManuallySelected("first", type))
        assertEquals(true, cache.isManuallySelected("second", type))
        cache.clear()
        cache.put("second", type, art("fresh"), CanonicalStatus.RESOLVED)

        // Then - selection state is removed and a fresh value can be stored
        assertEquals(false, cache.isManuallySelected("second", type))
        assertEquals("fresh", cache.get("second", type)?.result?.provider)
    }

    @Test fun `pinned Room positive cannot be replaced or shadowed by a negative`() = runTest {
        // Given - a manually selected positive value
        cache.put("key", type, art("chosen"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected("key", type)

        // When - automatic positive and negative writes target the same tuple
        cache.put("key", type, art("replacement"), CanonicalStatus.RESOLVED)
        cache.putNegative("key", type, EnrichmentResult.NotFound(type, "provider"), CanonicalStatus.RESOLVED, 1_000)

        // Then - the selected value remains and no negative answer is stored
        assertEquals("chosen", cache.get("key", type)?.result?.provider)
        assertNull(cache.getNegative("key", type))
    }

    @Test fun `a Room marker-only pin accepts its first positive fill`() = runTest {
        // Given - a selected marker without a positive row
        cache.markManuallySelected("key", type)

        // When - an automatic positive fill arrives
        cache.put("key", type, art("first"), CanonicalStatus.RESOLVED)

        // Then - the first value is retained
        assertEquals("first", cache.get("key", type)?.result?.provider)
    }

    private fun art(provider: String) = EnrichmentResult.Success(
        type,
        EnrichmentData.Artwork("https://example.test/$provider.jpg"),
        provider,
        0.9f,
    )
}
