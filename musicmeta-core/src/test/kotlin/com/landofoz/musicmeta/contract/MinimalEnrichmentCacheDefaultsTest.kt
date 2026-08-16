package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Overrides only [EnrichmentCache]'s six members declared with no body — [EnrichmentCache.get],
 * [EnrichmentCache.put], [EnrichmentCache.invalidate], [EnrichmentCache.isManuallySelected],
 * [EnrichmentCache.markManuallySelected] and [EnrichmentCache.clear] — and takes every other
 * member's default. `shapes.md` § 2 calls these "the four abstract members"; the interface declares
 * six, corrected 2026-08-16 against `EnrichmentCache.kt` rather than re-trusting that count.
 *
 * Stands in for a consumer's simplest legal implementation. `internal`, not `private`:
 * [com.landofoz.musicmeta.engine.EnrichmentCacheDefaultsSeamTest] reuses it.
 */
internal class MinimalEnrichmentCache : EnrichmentCache {
    private val entries = mutableMapOf<String, Pair<EnrichmentResult.Success, CanonicalStatus>>()
    private val manualSelections = mutableSetOf<String>()

    private fun key(entityKey: String, type: EnrichmentType) = "$entityKey:$type"

    override suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? =
        entries[key(entityKey, type)]?.let { (result, status) -> CacheEnvelope(result, status) }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        entries[key(entityKey, type)] = result to canonicalStatus
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        if (type != null) {
            entries.remove(key(entityKey, type))
            manualSelections.remove(key(entityKey, type))
        } else {
            entries.keys.removeAll { it.startsWith("$entityKey:") }
            manualSelections.removeAll { it.startsWith("$entityKey:") }
        }
    }

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean =
        key(entityKey, type) in manualSelections

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        manualSelections.add(key(entityKey, type))
    }

    override suspend fun clear() {
        entries.clear()
        manualSelections.clear()
    }
}

/**
 * What a consumer implementing only [EnrichmentCache]'s six bodyless members actually gets, pinned
 * so the gap is visible rather than discovered in production.
 *
 * **This is deliberately not an [EnrichmentCacheContract] subclass.** Run under the full contract it
 * fails four rules, because the negative-cache and stale-read defaults store nothing — `report.md`'s
 * F4, confirmed by running it. Maintainer ruling, 2026-08-16: assert the documented default here,
 * and take the question of whether the interface should grow working defaults to its own ticket,
 * because changing a default on a published interface alters behaviour for every existing custom
 * cache and needs an api-dump diff and a compatibility decision this test-only programme may not
 * make. Weakening the shared contract to accommodate this implementation was the option rejected:
 * the rules are right, and it is this implementation that declines them.
 */
class MinimalEnrichmentCacheDefaultsTest {

    private fun art() = EnrichmentResult.Success(
        type = EnrichmentType.ALBUM_ART,
        data = EnrichmentData.Artwork("https://example.test/art.jpg"),
        provider = "defaults-test",
        confidence = 0.9f,
    )

    private fun notFound() = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, provider = "defaults-test")

    @Test
    fun `a negative entry written to the default putNegative is not readable afterwards`() = runTest {
        // Given - a cache taking putNegative's and getNegative's defaults
        val cache = MinimalEnrichmentCache()

        // When - a negative entry is written and read back
        cache.putNegative("artist:1", EnrichmentType.ALBUM_ART, notFound(), CanonicalStatus.UNRESOLVED, TTL_MS)
        val stored = cache.getNegative("artist:1", EnrichmentType.ALBUM_ART)

        // Then - nothing comes back, because the write was a no-op and the read defaults to null
        assertNull(stored)
    }

    @Test
    fun `a live positive entry is invisible to the default getIncludingExpired`() = runTest {
        // Given - a cache taking getIncludingExpired's default, holding one unexpired entry
        val cache = MinimalEnrichmentCache()
        cache.put("artist:2", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)

        // When - the entry is read through both the ordinary and the stale-read paths
        val ordinary = cache.get("artist:2", EnrichmentType.ALBUM_ART)
        val stale = cache.getIncludingExpired("artist:2", EnrichmentType.ALBUM_ART)

        // Then - the ordinary read finds it and the stale-read path does not, so STALE_IF_ERROR has
        // nothing to serve through a cache that took this default
        assertNotNull(ordinary)
        assertNull(stale)
    }

    @Test
    fun `the six implemented members still behave, so the gap is only the defaults`() = runTest {
        // Given - a cache holding one entry and one manual selection
        val cache = MinimalEnrichmentCache()
        cache.put("artist:3", EnrichmentType.ALBUM_ART, art(), CanonicalStatus.RESOLVED, TTL_MS)
        cache.markManuallySelected("artist:3", EnrichmentType.ALBUM_ART)

        // When - the key is invalidated
        cache.invalidate("artist:3", EnrichmentType.ALBUM_ART)

        // Then - both the entry and the selection are gone, so nothing here is failing generally
        assertNull(cache.get("artist:3", EnrichmentType.ALBUM_ART))
        assertFalse(cache.isManuallySelected("artist:3", EnrichmentType.ALBUM_ART))
    }

    private companion object {
        const val TTL_MS = 60_000L
    }
}
