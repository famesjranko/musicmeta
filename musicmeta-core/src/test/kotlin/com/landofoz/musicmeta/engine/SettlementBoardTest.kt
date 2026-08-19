package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SettlementBoard.settle]'s lock must cover only the map write, the deferred completion, and the
 * snapshot — never [finalize] itself, which runs consumer-supplied suspend I/O
 * (`CatalogProvider.checkAvailability`, a stale-cache read) for an arbitrary settling type.
 */
class SettlementBoardTest {

    // InjectDispatcher: a real dispatcher is the point — this measures wall-clock contention a
    // single-threaded test dispatcher cannot exhibit.
    @Suppress("InjectDispatcher")
    @Test
    fun `a slow finalize for one type does not delay another type's settle`() {
        // Given - two types on one board; one's finalize is slow (stands in for a consumer's
        // CatalogProvider.checkAvailability), the other's does no work of its own. slowStarted pins
        // the ordering deterministically — not a race on which coroutine reaches the lock first.
        val slowType = EnrichmentType.SIMILAR_ARTISTS
        val fastType = EnrichmentType.ARTIST_BIO
        val board = SettlementBoard(setOf(slowType, fastType))
        val slowStarted = CompletableDeferred<Unit>()
        fun notFound(t: EnrichmentType) = EnrichmentResult.NotFound(t, "p")

        runBlocking {
            // When - the slow type's finalize is already running when the fast type settles
            val slow = launch(Dispatchers.Default) {
                board.settle(slowType, notFound(slowType), null) {
                    slowStarted.complete(Unit)
                    delay(300)
                    it
                }
            }
            slowStarted.await()
            val start = System.nanoTime()
            board.settle(fastType, notFound(fastType), null) { it }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            // Then - the fast type's own settle did not wait behind the slow type's finalize
            assertTrue(
                "fast settle took ${elapsedMs}ms; a wide lock would serialize it behind the slow finalize",
                elapsedMs < 150,
            )
            slow.join()
        }
    }

    // InjectDispatcher: a real dispatcher is the point — this is the same class of race the
    // engine-level test pins, but observes `executions` directly, which no public surface exposes.
    @Suppress("InjectDispatcher")
    @Test
    fun `results and executions both survive many types settling at once, gated on one release`() {
        // Given - 32 types on one board, each with its own distinct ChainExecution — gated on one
        // release so every settle() call reaches the board together, the maximal race window,
        // rather than however a back-to-back launch loop happens to schedule them
        val types = EnrichmentType.entries.take(32).toSet()
        val iterations = 200
        var contentMismatchCount = 0

        repeat(iterations) {
            val board = SettlementBoard(types)
            val remaining = AtomicInteger(types.size)
            val gate = CompletableDeferred<Unit>()

            runBlocking {
                val jobs = types.map { type ->
                    launch(Dispatchers.Default) {
                        if (remaining.decrementAndGet() == 0) gate.complete(Unit)
                        gate.await()
                        val execution = ChainExecution(listOf(type.name), emptyMap(), emptyList())
                        board.settle(type, EnrichmentResult.NotFound(type, type.name), execution) { it }
                    }
                }
                jobs.joinAll()
            }

            // Then - every type's own result and its own execution both survived intact
            val results = runBlocking { board.snapshotResults() }
            val executions = runBlocking { board.snapshotExecutions() }
            val mismatch = types.any { type ->
                val result = results[type] as? EnrichmentResult.NotFound
                val execution = executions[type]
                result?.provider != type.name || execution?.attemptedProviderIds != listOf(type.name)
            }
            if (mismatch) contentMismatchCount++
        }

        assertEquals(
            "iterations with wrong results/executions content: $contentMismatchCount of $iterations",
            0,
            contentMismatchCount,
        )
    }
}
