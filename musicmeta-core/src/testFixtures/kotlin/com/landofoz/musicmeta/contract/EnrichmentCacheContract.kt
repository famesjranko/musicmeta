package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.ContractSuite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract every [EnrichmentCache] implementation owes its callers, asserted once here and
 * inherited by each implementation's own subclass. A new backend gets this coverage by existing —
 * see [EnrichmentCache]'s own KDoc for the prose version of these rules.
 */
abstract class EnrichmentCacheContract : ContractSuite<EnrichmentCache>() {

    private fun art(url: String = "https://example.test/art.jpg") = EnrichmentResult.Success(
        type = EnrichmentType.ALBUM_ART,
        data = EnrichmentData.Artwork(url),
        provider = "contract-test",
        confidence = 0.9f,
    )

    private fun notFound(provider: String = "contract-test") =
        EnrichmentResult.NotFound(type = EnrichmentType.ALBUM_ART, provider = provider)

    @Test
    fun `a put entry's data is returned by get`() = runTest {
        // Given - a fresh cache holding one stored entry
        val cache = subject()
        try {
            cache.put("artist:1", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)

            // When - the same key and type are read back
            val envelope = cache.get("artist:1", EnrichmentType.ALBUM_ART)

            // Then - the stored payload comes back unchanged
            // Compared on `data` rather than the whole `Success`: `LookupProvenance.CACHE`'s own
            // KDoc allows an implementation to relabel provenance to CACHE on every read instead of
            // preserving what was stored. That is a per-implementation difference the contract
            // permits, not a violation.
            assertEquals(art().data, envelope?.result?.data)
        } finally {
            release(cache)
        }
    }

    @Test
    fun `invalidate clears the stored entry`() = runTest {
        // Given - a cache holding one entry for a key and type
        val cache = subject()
        try {
            cache.put("artist:2", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)

            // When - that key and type are invalidated
            cache.invalidate("artist:2", EnrichmentType.ALBUM_ART)

            // Then - the entry no longer reads back
            assertNull(cache.get("artist:2", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `clear removes a previously put entry`() = runTest {
        // Given - a cache holding one entry
        val cache = subject()
        try {
            cache.put("artist:3", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)

            // When - the whole cache is cleared
            cache.clear()

            // Then - the entry no longer reads back
            assertNull(cache.get("artist:3", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `invalidate removes the negative entry for that key`() = runTest {
        // Given - a cache holding a negative entry for one key and type
        val cache = subject()
        try {
            cache.putNegative("artist:4", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.UNRESOLVED, TTL_MS)

            // When - that key and type are invalidated
            cache.invalidate("artist:4", EnrichmentType.ALBUM_ART)

            // Then - the negative entry no longer reads back
            assertNull(cache.getNegative("artist:4", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `invalidate leaves an unrelated key's positive and negative entries untouched`() = runTest {
        // Given - two different keys, one holding a positive entry, the other a negative one
        val cache = subject()
        try {
            cache.put("artist:5", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)
            cache.putNegative("artist:6", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.UNRESOLVED, TTL_MS)

            // When - a third, unrelated key is invalidated
            cache.invalidate("artist:not-a-match", EnrichmentType.ALBUM_ART)

            // Then - both unrelated keys still read back
            assertEquals(art().data, cache.get("artist:5", EnrichmentType.ALBUM_ART)?.result?.data)
            assertNotNull(cache.getNegative("artist:6", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `invalidate clears manual-selection state for the addressed key`() = runTest {
        // Given - a key marked manually selected
        val cache = subject()
        try {
            cache.markManuallySelected("artist:7", EnrichmentType.ALBUM_ART)

            // When - that key and type are invalidated
            cache.invalidate("artist:7", EnrichmentType.ALBUM_ART)

            // Then - the key no longer reports as manually selected
            assertFalse(cache.isManuallySelected("artist:7", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    /**
     * `open` solely so a backend with a known, ticketed defect can carry an `@Ignore` against this
     * one rule without losing the other thirteen. An override that changes what is asserted is still
     * forbidden; the only sanctioned override calls straight back to this body.
     */
    @Test
    open fun `manual selection survives an ordinary write`() = runTest {
        // Given - a key marked manually selected
        val cache = subject()
        try {
            cache.markManuallySelected("artist:8", EnrichmentType.ALBUM_ART)

            // When - a fresh value is written for that same key and type
            cache.put("artist:8", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)

            // Then - the manual-selection marker is still set
            assertTrue(cache.isManuallySelected("artist:8", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `clear removes negative entries`() = runTest {
        // Given - a cache holding a negative entry
        val cache = subject()
        try {
            cache.putNegative("artist:9", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.UNRESOLVED, TTL_MS)

            // When - the whole cache is cleared
            cache.clear()

            // Then - the negative entry no longer reads back
            assertNull(cache.getNegative("artist:9", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `clear removes manual-selection state`() = runTest {
        // Given - a key marked manually selected
        val cache = subject()
        try {
            cache.markManuallySelected("artist:10", EnrichmentType.ALBUM_ART)

            // When - the whole cache is cleared
            cache.clear()

            // Then - the key no longer reports as manually selected
            assertFalse(cache.isManuallySelected("artist:10", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `a put negative entry is returned by getNegative`() = runTest {
        // Given - a fresh cache holding one negative entry
        val cache = subject()
        try {
            cache.putNegative("artist:11", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.UNRESOLVED, TTL_MS)

            // When - the same key and type are read back through the negative channel
            val envelope = cache.getNegative("artist:11", EnrichmentType.ALBUM_ART)

            // Then - a negative entry comes back
            assertNotNull(envelope)
        } finally {
            release(cache)
        }
    }

    @Test
    fun `a negative entry does not satisfy a positive get`() = runTest {
        // Given - a cache holding only a negative entry for a key and type
        val cache = subject()
        try {
            cache.putNegative("artist:12", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.UNRESOLVED, TTL_MS)

            // When - the positive channel is read for that same key and type
            val positive = cache.get("artist:12", EnrichmentType.ALBUM_ART)

            // Then - the positive read misses; a NotFound never flows through the Success-typed path
            assertNull(positive)
        } finally {
            release(cache)
        }
    }

    @Test
    fun `clear leaves nothing for getIncludingExpired to resurrect`() = runTest {
        // Given - a cache holding one entry, confirmed visible through the expired-read path too
        val cache = subject()
        try {
            cache.put("artist:13", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)
            assertNotNull(cache.getIncludingExpired("artist:13", EnrichmentType.ALBUM_ART))

            // When - the whole cache is cleared
            cache.clear()

            // Then - the expired-read path no longer resurrects the entry either
            assertNull(cache.getIncludingExpired("artist:13", EnrichmentType.ALBUM_ART))
        } finally {
            release(cache)
        }
    }

    @Test
    fun `get preserves the exact canonicalStatus a positive entry was put under`() = runTest {
        // Given - an entry put under a canonicalStatus other than the fallback getNegative/get use
        val cache = subject()
        try {
            cache.put("artist:14", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.AMBIGUOUS, TTL_MS)

            // When - the same key and type are read back
            val envelope = cache.get("artist:14", EnrichmentType.ALBUM_ART)

            // Then - the exact status put in comes back, not a fallback or a relabelled one
            assertEquals(CanonicalStatus.AMBIGUOUS, envelope?.canonicalStatus)
        } finally {
            release(cache)
        }
    }

    @Test
    fun `getNegative preserves the exact canonicalStatus a negative entry was put under`() = runTest {
        // Given - a negative entry put under a canonicalStatus other than the fallback
        val cache = subject()
        try {
            cache.putNegative("artist:15", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.AMBIGUOUS, TTL_MS)

            // When - the same key and type are read back through the negative channel
            val envelope = cache.getNegative("artist:15", EnrichmentType.ALBUM_ART)

            // Then - the exact status put in comes back, not a fallback
            assertEquals(CanonicalStatus.AMBIGUOUS, envelope?.canonicalStatus)
        } finally {
            release(cache)
        }
    }

    private companion object {
        /** Long enough that no rule here can pass or fail because of expiry timing. */
        const val TTL_MS = 60_000L
    }
}
