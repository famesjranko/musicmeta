package com.landofoz.musicmeta.cache

import com.landofoz.musicmeta.CanonicalStatus
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
        // Given - empty cache with no entries

        // When - requesting a key that was never stored
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then - returns null
        assertNull(result)
    }

    @Test fun `put then get returns stored result`() = runTest {
        // Given - one entry stored in cache
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 60_000)

        // When - retrieving the same key
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then - the cached result is returned
        assertNotNull(result)
    }

    @Test fun `get reads back the canonicalStatus put wrote, closing the write-only gap`() = runTest {
        // Given - an entry stored under a specific canonical status
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, 60_000)

        // When - retrieving the same key
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then - the envelope carries the exact status it was written under, not just the result
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, result?.canonicalStatus)
    }

    @Test fun `get returns null for expired entry`() = runTest {
        // Given - entry with 5s TTL, and clock advanced past expiry
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 5000)
        time += 6000

        // When - retrieving the expired entry
        val result = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then - returns null because TTL elapsed
        assertNull(result)
    }

    @Test fun `evicts LRU when capacity exceeded`() = runTest {
        // Given - cache filled to capacity (3), with a:1 touched most recently
        cache.put("a:1", EnrichmentType.ALBUM_ART, art("1"), CanonicalStatus.RESOLVED, 60_000)
        cache.put("a:2", EnrichmentType.ALBUM_ART, art("2"), CanonicalStatus.RESOLVED, 60_000)
        cache.put("a:3", EnrichmentType.ALBUM_ART, art("3"), CanonicalStatus.RESOLVED, 60_000)
        cache.get("a:1", EnrichmentType.ALBUM_ART) // Touch a:1

        // When - inserting a 4th entry beyond capacity
        cache.put("a:4", EnrichmentType.ALBUM_ART, art("4"), CanonicalStatus.RESOLVED, 60_000)

        // Then - least-recently-used (a:2) is evicted, a:1 survives
        assertNotNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertNull(cache.get("a:2", EnrichmentType.ALBUM_ART))
    }

    @Test fun `invalidate specific type`() = runTest {
        // Given - same key stored with two different enrichment types
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 60_000)
        cache.put("a:1", EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "test", 0.9f), CanonicalStatus.RESOLVED, 60_000)

        // When - invalidating only the ALBUM_ART type
        cache.invalidate("a:1", EnrichmentType.ALBUM_ART)

        // Then - ALBUM_ART is removed but GENRE remains
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get("a:1", EnrichmentType.GENRE))
    }

    @Test fun `invalidate all types`() = runTest {
        // Given - a cached entry
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 60_000)

        // When - invalidating with null type (all types)
        cache.invalidate("a:1", null)

        // Then - all entries for that key are removed
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `manual selection flag`() = runTest {
        // Given - no manual selection flag set
        assertFalse(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))

        // When - marking a key as manually selected
        cache.markManuallySelected("a:1", EnrichmentType.ALBUM_ART)

        // Then - the flag is persisted
        assertTrue(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `invalidate removes manual selection for the addressed types`() = runTest {
        // Given - manual flags for two types on one entity and another entity's control
        cache.markManuallySelected("a:1", EnrichmentType.ALBUM_ART)
        cache.markManuallySelected("a:1", EnrichmentType.ARTIST_PHOTO)
        cache.markManuallySelected("b:1", EnrichmentType.ALBUM_ART)

        // When - invalidating one type for the first entity
        cache.invalidate("a:1", EnrichmentType.ALBUM_ART)

        // Then - only that type's flag is removed
        assertFalse(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))
        assertTrue(cache.isManuallySelected("a:1", EnrichmentType.ARTIST_PHOTO))
        assertTrue(cache.isManuallySelected("b:1", EnrichmentType.ALBUM_ART))

        // When - invalidating every remaining type for the first entity
        cache.invalidate("a:1")

        // Then - the other entity's flag remains
        assertFalse(cache.isManuallySelected("a:1", EnrichmentType.ARTIST_PHOTO))
        assertTrue(cache.isManuallySelected("b:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `clear removes everything`() = runTest {
        // Given - cache with entries and manual selection flags
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 60_000)
        cache.markManuallySelected("a:1", EnrichmentType.ALBUM_ART)

        // When - clearing the entire cache
        cache.clear()

        // Then - both cached entries and manual flags are gone
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertFalse(cache.isManuallySelected("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `get returns null for expired entry but does not remove it`() = runTest {
        // Given - entry with 5s TTL, clock advanced past expiry
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 5000)
        time += 6000

        // When - get() returns null for the expired entry
        val getResult = cache.get("a:1", EnrichmentType.ALBUM_ART)

        // Then - get() returns null, but the entry is retained so getIncludingExpired can still find it
        assertNull(getResult)
        assertNotNull(cache.getIncludingExpired("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `getIncludingExpired returns expired entry`() = runTest {
        // Given - entry stored with 5s TTL, clock advanced past expiry
        val stored = art("expired-url")
        cache.put("a:1", EnrichmentType.ALBUM_ART, stored, CanonicalStatus.RESOLVED, 5000)
        time += 6000

        // When - retrieving via getIncludingExpired
        val result = cache.getIncludingExpired("a:1", EnrichmentType.ALBUM_ART)

        // Then - the stale entry is returned with correct data
        assertNotNull(result)
        assertEquals(stored.data, result!!.result.data)
    }

    @Test fun `getIncludingExpired returns null for never-cached key`() = runTest {
        // Given - empty cache, no entry ever stored for this key

        // When - calling getIncludingExpired on an unknown key
        val result = cache.getIncludingExpired("missing:1", EnrichmentType.ALBUM_ART)

        // Then - returns null because no entry exists at all
        assertNull(result)
    }

    private fun notFound() = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "test")

    @Test fun `getNegative returns null for uncached key`() = runTest {
        // Given - empty cache with no negative entries

        // When - reading a negative that was never stored
        val result = cache.getNegative("a:1", EnrichmentType.ALBUM_ART)

        // Then - null, a miss
        assertNull(result)
    }

    @Test fun `putNegative then getNegative returns the stored NotFound`() = runTest {
        // Given - one negative entry stored in the cache
        cache.putNegative("a:1", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.RESOLVED, 60_000)

        // When - reading the same key back
        val result = cache.getNegative("a:1", EnrichmentType.ALBUM_ART)

        // Then - the stored NotFound and its written canonicalStatus are both returned
        assertEquals(notFound(), result?.result)
        assertEquals(CanonicalStatus.RESOLVED, result?.canonicalStatus)
    }

    @Test fun `getNegative returns null for an expired entry`() = runTest {
        // Given - a negative entry stored with a 5s TTL
        cache.putNegative("a:1", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.RESOLVED, 5000)

        // When - the clock advances past expiry and the entry is read
        time += 6000
        val result = cache.getNegative("a:1", EnrichmentType.ALBUM_ART)

        // Then - null: an expired negative is never served
        assertNull(result)
    }

    @Test fun `invalidate with a type removes the negative entry for that type`() = runTest {
        // Given - a negative entry stored for ALBUM_ART
        cache.putNegative("a:1", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.RESOLVED, 60_000)

        // When - invalidating that type
        cache.invalidate("a:1", EnrichmentType.ALBUM_ART)

        // Then - the negative entry is gone
        assertNull(cache.getNegative("a:1", EnrichmentType.ALBUM_ART))
    }

    @Test fun `invalidate with null type removes negative entries for every type`() = runTest {
        // Given - negative entries stored for two types under the same key
        cache.putNegative("a:1", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.RESOLVED, 60_000)
        cache.putNegative("a:1", EnrichmentType.GENRE, EnrichmentResult.NotFound(EnrichmentType.GENRE, "test"), CanonicalStatus.RESOLVED, 60_000)

        // When - invalidating the key with no type given
        cache.invalidate("a:1")

        // Then - both negative entries are gone
        assertNull(cache.getNegative("a:1", EnrichmentType.ALBUM_ART))
        assertNull(cache.getNegative("a:1", EnrichmentType.GENRE))
    }

    @Test fun `clear removes negative entries alongside positive ones`() = runTest {
        // Given - a positive and a negative entry both stored
        cache.put("a:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, 60_000)
        cache.putNegative("a:2", EnrichmentType.GENRE, EnrichmentResult.NotFound(EnrichmentType.GENRE, "test"), CanonicalStatus.RESOLVED, 60_000)

        // When - clearing the entire cache
        cache.clear()

        // Then - both are gone
        assertNull(cache.get("a:1", EnrichmentType.ALBUM_ART))
        assertNull(cache.getNegative("a:2", EnrichmentType.GENRE))
    }
}
