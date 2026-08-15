package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.ContractSuite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The contract every [EnrichmentCache] implementation owes its callers, asserted once here and
 * inherited by each implementation's own subclass. A new backend gets this coverage by existing.
 *
 * **Two rules, deliberately.** This is the kit's worked example for [ContractSuite] — enough to
 * prove the base carries rules across a module boundary, and no more. The full cache contract is
 * grown here by the ticket that owns it; a worked example that swells into a complete suite has
 * eaten that ticket and made the kit unreviewable.
 */
abstract class EnrichmentCacheContract : ContractSuite<EnrichmentCache>() {

    private fun art(url: String = "https://example.test/art.jpg") = EnrichmentResult.Success(
        type = EnrichmentType.ALBUM_ART,
        data = EnrichmentData.Artwork(url),
        provider = "contract-test",
        confidence = 0.9f,
    )

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
            // KDoc allows an implementation that did not preserve the original provenance to
            // relabel it on every read, which the Room backing does and the in-memory ones do not.
            // That is a per-implementation difference the contract permits, not a violation.
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

    private companion object {
        /** Long enough that no rule here can pass or fail because of expiry timing. */
        const val TTL_MS = 60_000L
    }
}
