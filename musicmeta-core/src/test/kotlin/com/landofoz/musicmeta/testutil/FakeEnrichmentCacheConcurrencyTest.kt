package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing writes [FakeEnrichmentCache] from more than one coroutine today — the write-back path has
 * no `launch` — so these pin the store against the caller that arrives later rather than a defect
 * that exists now. The flip is silent: a fan-out over the write-back changes no line of the double,
 * and a lost `put` surfaces as an engine test asserting on whichever entry won.
 *
 * Every claim here is a count or a set, never a duration (`docs/pitfalls.md` §38). `runBlocking`,
 * not `runTest`: the race only exists on a real dispatcher.
 */
class FakeEnrichmentCacheConcurrencyTest {

    private val type = EnrichmentType.ALBUM_ART
    private val success = EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x.test/art.jpg"), "p", 0.9f)
    private val notFound = EnrichmentResult.NotFound(type, "p")

    @Test
    fun `concurrent puts all land in the positive store`() = runBlocking {
        // Given - a key per write, so a lost put is a missing key rather than an overwrite
        val keys = entityKeys()
        val expected = keys.flatten().map { "$it:$type" }.toSet()

        repeat(TRIALS) { trial ->
            val cache = FakeEnrichmentCache()

            // When - four coroutines on real threads put as simultaneously as a spin barrier can
            // arrange
            fanOutOnRealThreads(THREADS) { thread ->
                for (key in keys[thread]) {
                    cache.put(key, type, success, CanonicalStatus.RESOLVED, TTL_MS)
                }
            }

            // Then - all three maps `put` writes hold every entry
            assertEquals("trial $trial lost a result", expected, cache.stored.keys.toSet())
            assertEquals("trial $trial lost a TTL", expected, cache.storedTtls.keys.toSet())
            assertEquals("trial $trial lost a status", expected, cache.storedStatuses.keys.toSet())
        }
    }

    @Test
    fun `concurrent negative puts and manual selections all land`() = runBlocking {
        // Given - a key per write across the negative store and the manual-selection set
        val keys = entityKeys()
        val expected = keys.flatten().map { "$it:$type" }.toSet()

        repeat(TRIALS) { trial ->
            val cache = FakeEnrichmentCache()

            // When - four coroutines on real threads write both stores at once
            fanOutOnRealThreads(THREADS) { thread ->
                for (key in keys[thread]) {
                    cache.putNegative(key, type, notFound, CanonicalStatus.RESOLVED, TTL_MS)
                    cache.markManuallySelected(key, type)
                }
            }

            // Then - neither store lost a write to a racing writer
            assertEquals("trial $trial lost a negative result", expected, cache.negativeStored.keys.toSet())
            assertEquals("trial $trial lost a negative status", expected, cache.negativeStatuses.keys.toSet())
            assertTrue(
                "trial $trial lost a manual selection",
                keys.flatten().all { cache.isManuallySelected(it, type) },
            )
        }
    }

    private fun entityKeys() = (0 until THREADS).map { thread ->
        (0 until WRITES_PER_THREAD).map { "album:artist-$thread:album-$it" }
    }

    private companion object {
        /** The width of an engine's per-type fan-out, the concurrency this double would meet. */
        const val THREADS = 4

        /**
         * A run of writes per thread, and trials well past the one a lost write first appears on.
         * A single four-way race is lost rarely enough that one trial proves nothing and cheaply
         * enough that thousands cost under a second, so the budget is sized for margin rather than
         * for a rate, which is a property of the machine and decays.
         */
        const val WRITES_PER_THREAD = 50
        const val TRIALS = 2_000
        const val TTL_MS = 60_000L
    }
}
