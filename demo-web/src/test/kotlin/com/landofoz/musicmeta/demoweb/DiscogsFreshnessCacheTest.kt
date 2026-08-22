package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The six-hour display ceiling Discogs' API terms put on their content, and the obligations the
 * [EnrichmentCache] KDoc puts on anything that delegates to another cache.
 */
class DiscogsFreshnessCacheTest {

    private val now = AtomicLong(1_000_000L)
    private fun advance(ms: Long) = now.addAndGet(ms)

    private fun backing() = InMemoryEnrichmentCache(clock = { now.get() })

    private val discogsResult = EnrichmentResult.Success(
        type = EnrichmentType.LABEL,
        data = EnrichmentData.Metadata(label = "RCA Victor"),
        provider = DISCOGS_ID,
        confidence = 0.8f,
    )

    /** A merged result whose winner is another provider but whose payload still credits Discogs. */
    private val discogsAttributedResult = EnrichmentResult.Success(
        type = EnrichmentType.GENRE,
        data = EnrichmentData.Metadata(
            genreTags = listOf(GenreTag("Glam Rock", 0.9f, sources = listOf("musicbrainz", DISCOGS_ID))),
        ),
        provider = "musicbrainz",
        confidence = 0.9f,
    )

    private val cleanResult = EnrichmentResult.Success(
        type = EnrichmentType.GENRE,
        data = EnrichmentData.Metadata(
            genreTags = listOf(GenreTag("Glam Rock", 0.9f, sources = listOf("musicbrainz"))),
        ),
        provider = "musicbrainz",
        confidence = 0.9f,
    )

    private suspend fun EnrichmentCache.store(result: EnrichmentResult.Success, ttlMs: Long = 30L * 24 * 3600 * 1000) =
        put("album:hunky-dory", result.type, result, CanonicalStatus.RESOLVED, ttlMs)

    @Test fun `a Discogs sourced entry stops being served once six hours pass`() {
        runBlocking {
            // Given - a Discogs result written through the ceiling on a thirty-day TTL
            val cache = DiscogsFreshnessCache(backing())
            cache.store(discogsResult)

            // When - the clock moves an hour past the ceiling
            advance(DISCOGS_FRESHNESS_CEILING_MS + 3_600_000L)

            // Then - the entry is gone, so nothing Discogs sourced can be displayed from it
            assertNull(cache.get("album:hunky-dory", EnrichmentType.LABEL))
        }
    }

    @Test fun `the same entry is still served at six hours without the ceiling`() {
        runBlocking {
            // Given - the same Discogs result written straight to the undecorated cache
            val cache = backing()
            cache.store(discogsResult)

            // When - the clock moves an hour past the ceiling
            advance(DISCOGS_FRESHNESS_CEILING_MS + 3_600_000L)

            // Then - it is served as a local run serves it today, which is what the ceiling changes
            assertNotNull(cache.get("album:hunky-dory", EnrichmentType.LABEL))
        }
    }

    @Test fun `a Discogs sourced entry is still served inside the ceiling`() {
        runBlocking {
            // Given - a Discogs result written through the ceiling
            val cache = DiscogsFreshnessCache(backing())
            cache.store(discogsResult)

            // When - the clock moves to an hour short of the ceiling
            advance(DISCOGS_FRESHNESS_CEILING_MS - 3_600_000L)

            // Then - it is served, so the ceiling is a cap and not an eviction on write
            assertEquals(discogsResult, cache.get("album:hunky-dory", EnrichmentType.LABEL)?.result)
        }
    }

    @Test fun `a payload crediting Discogs is capped even when another provider won the merge`() {
        runBlocking {
            // Given - a merged result attributed to MusicBrainz whose tags name Discogs as a source
            val cache = DiscogsFreshnessCache(backing())
            cache.store(discogsAttributedResult)

            // When - the clock moves past the ceiling
            advance(DISCOGS_FRESHNESS_CEILING_MS + 1)

            // Then - it is gone: the winning provider's name is not the whole attribution
            assertNull(cache.get("album:hunky-dory", EnrichmentType.GENRE))
        }
    }

    @Test fun `an entry naming no Discogs source keeps the TTL the engine asked for`() {
        runBlocking {
            // Given - a result with no Discogs attribution anywhere in it
            val cache = DiscogsFreshnessCache(backing())
            cache.store(cleanResult)

            // When - the clock moves well past the ceiling but inside the requested TTL
            advance(DISCOGS_FRESHNESS_CEILING_MS * 4)

            // Then - it is still served, so the ceiling costs only what it has to
            assertNotNull(cache.get("album:hunky-dory", EnrichmentType.GENRE))
        }
    }

    @Test fun `the stale read path will not resurrect a Discogs entry past the ceiling`() {
        runBlocking {
            // Given - a Discogs result written through the ceiling
            val cache = DiscogsFreshnessCache(backing())
            cache.store(discogsResult)

            // When - the clock moves past the ceiling and STALE_IF_ERROR's expired read runs
            advance(DISCOGS_FRESHNESS_CEILING_MS + 1)
            val stale = cache.getIncludingExpired("album:hunky-dory", EnrichmentType.LABEL)

            // Then - nothing comes back, so a cache-mode swap cannot lift the display ceiling
            assertNull(stale)
        }
    }

    @Test fun `the stale read path still serves an expired entry naming no Discogs source`() {
        runBlocking {
            // Given - a short-lived result with no Discogs attribution
            val cache = DiscogsFreshnessCache(backing())
            cache.store(cleanResult, ttlMs = 1_000L)

            // When - the clock moves past its own TTL and the expired read runs
            advance(60_000L)
            val stale = cache.getIncludingExpired("album:hunky-dory", EnrichmentType.GENRE)

            // Then - STALE_IF_ERROR keeps the fallback it has today
            assertEquals(cleanResult, stale?.result)
        }
    }

    @Test fun `negative entries, selections and invalidation all reach the delegate`() {
        runBlocking {
            // Given - a ceiling wrapped around a cache, carrying a negative entry and a selection
            val backing = backing()
            val cache = DiscogsFreshnessCache(backing)
            val notFound = EnrichmentResult.NotFound(EnrichmentType.LABEL, DISCOGS_ID)
            cache.putNegative("album:hunky-dory", EnrichmentType.LABEL, notFound, CanonicalStatus.RESOLVED, 60_000L)
            cache.markManuallySelected("album:hunky-dory", EnrichmentType.LABEL)

            // When - each is read back through the wrapper and then invalidated
            val negativeBefore = cache.getNegative("album:hunky-dory", EnrichmentType.LABEL)
            val selectedBefore = cache.isManuallySelected("album:hunky-dory", EnrichmentType.LABEL)
            cache.invalidate("album:hunky-dory", EnrichmentType.LABEL)

            // Then - both were forwarded, and the invalidation cleared them in the delegate itself
            assertEquals(notFound, negativeBefore?.result)
            assertEquals(true, selectedBefore)
            assertNull(backing.getNegative("album:hunky-dory", EnrichmentType.LABEL))
            assertEquals(false, backing.isManuallySelected("album:hunky-dory", EnrichmentType.LABEL))
        }
    }

    @Test fun `clear reaches the delegate`() {
        runBlocking {
            // Given - a ceiling wrapped around a cache holding one entry
            val backing = backing()
            val cache = DiscogsFreshnessCache(backing)
            cache.store(cleanResult)

            // When - the wrapper is cleared
            cache.clear()

            // Then - the delegate is empty, not just the wrapper
            assertNull(backing.get("album:hunky-dory", EnrichmentType.GENRE))
        }
    }
}
