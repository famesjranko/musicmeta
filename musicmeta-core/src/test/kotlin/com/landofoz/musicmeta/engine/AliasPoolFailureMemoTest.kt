package com.landofoz.musicmeta.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * What an alias source that does not answer costs. Every provider in the fan-out reads the pool, so
 * a source left unresolved by a failure is one MusicBrainz retry ladder per reader, serialised
 * behind one lock and charged to the same `enrichTimeoutMs`.
 */
class AliasPoolFailureMemoTest {

    @Test
    fun `a source that failed is not asked again by the next reader`() = runTest {
        // Given - a source that throws once and would answer a second reader
        var calls = 0
        val names = ResolvedEntityNames()
        names.offerAliases {
            calls++
            if (calls == 1) throw IOException("MusicBrainz is down")
            POOL
        }

        // When - two readers ask for the pool in one call
        val first = withContext(names) { names.aliases() }
        val second = withContext(names) { names.aliases() }

        // Then - the failure is the call's answer, bought once
        assertEquals(emptyList<AlternativeName>(), first)
        assertEquals(emptyList<AlternativeName>(), second)
        assertEquals(1, calls)
    }

    @Test
    fun `our own cancellation reaches the reader and leaves the pool unresolved`() = runTest {
        // Given - a source whose first reader is cancelled while it runs
        var calls = 0
        val names = ResolvedEntityNames()
        names.offerAliases {
            calls++
            if (calls == 1) {
                currentCoroutineContext().cancel()
                throw CancellationException("the reader's job was cancelled")
            }
            POOL
        }
        val isolated = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))

        // When - the cancelled reader asks, and a healthy reader asks after it
        val thrown = runCatching { isolated.async(names) { names.aliases() }.await() }.exceptionOrNull()
        val recovered = withContext(names) { names.aliases() }

        // Then - cancellation propagated rather than being held as an empty pool
        assertTrue("expected cancellation to propagate, got $thrown", thrown is CancellationException)
        assertEquals(POOL, recovered)
        assertEquals(2, calls)
    }

    private companion object {
        val POOL = listOf(AlternativeName("Tokyo Jihen", official = true))
    }
}
