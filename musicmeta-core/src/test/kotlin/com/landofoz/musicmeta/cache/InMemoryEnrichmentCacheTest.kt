package com.landofoz.musicmeta.cache

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InMemoryEnrichmentCacheTest {
    private var time = 1000L
    private val cache = InMemoryEnrichmentCache(maxEntries = 3, clock = { time })
    private fun art(url: String = "url") = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork(url), "test", 0.95f)

    @Test fun `get returns null for uncached key`() = runTest {
        // Given — empty cache with no entries

        // When — requesting a key that was never stored
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then — returns null
        assertNull(result)
    }

    @Test fun `put then get returns stored result`() = runTest {
        // Given — one entry stored in cache
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), 60_000)

        // When — retrieving the same key
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then — the cached result is returned
        assertNotNull(result)
    }

    @Test fun `get returns null for expired entry`() = runTest {
        // Given — entry with 5s TTL, and clock advanced past expiry
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), 5000)
        time += 6000

        // When — retrieving the expired entry
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then — returns null because TTL elapsed
        assertNull(result)
    }

    @Test fun `evicts LRU when capacity exceeded`() = runTest {
        // Given — cache filled to capacity (3), with a:1 touched most recently
        cache.put("a:1", EnrichmentType.ALBUM_ART, art("1"), 60_000)
        cache.put("a:2", EnrichmentType.ALBUM_ART, art("2"), 60_000)
        cache.put("a:3", EnrichmentType.ALBUM_ART, art("3"), 60_000)
        cache.get("a:1", EnrichmentType.ALBUM_ART) // Touch a:1

        // When — inserting a 4th entry beyond capacity
        cache.put("a:4", EnrichmentType.ALBUM_ART, art("4"), 60_000)

        // Then — least-recently-used (a:2) is evicted, a:1 survives
        assertNotNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertNull(cache.get("a:2", EnrichmentType.ALBUM_ART))
    }

    @Test fun `invalidate specific type`() = runTest {
        // Given — same key stored with two different enrichment types
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), 60_000)
        cache.put("a:1", EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "test", 0.9f), 60_000)

        // When — invalidating only the ALBUM_ART type
        cache.invalidate("a:1", EnrichmentType.ALBUM_ART)

        // Then — ALBUM_ART is removed but GENRE remains
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get("a:1", EnrichmentType.GENRE))
    }

    @Test fun `invalidate all types`() = runTest {
        // Given — a cached entry
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), 60_000)

        // When — invalidating with null type (all types)
        cache.invalidate("a:1", null)

        // Then — all entries for that key are removed
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `manual selection flag`() = runTest {
        // Given — no manual selection flag set
        assertFalse(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))

        // When — marking a key as manually selected
        cache.markManuallySelected("a:1", EnrichmentType.ALBUM_ART)

        // Then — the flag is persisted
        assertTrue(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `clear removes everything`() = runTest {
        // Given — cache with entries and manual selection flags
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), 60_000)
        cache.markManuallySelected("a:1", EnrichmentType.ALBUM_ART)

        // When — clearing the entire cache
        cache.clear()

        // Then — both cached entries and manual flags are gone
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertFalse(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))
    }
}
