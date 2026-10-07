package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
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
