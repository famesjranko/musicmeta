package com.landofoz.musicmeta.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
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

    // No dispatcher: the shared lookup runs on the reader's, so this carries only the job that owns
    // a lookup outliving the reader that opened it.
    private val lookupScope = CoroutineScope(SupervisorJob())

    @After fun stopLookups() {
        lookupScope.cancel()
    }

    @Test
    fun `a source that failed is not asked again by the next reader`() = runTest {
        // Given - a source that throws once and would answer a second reader
        var calls = 0
        val names = ResolvedEntityNames(lookupScope)
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
    fun `our own cancellation reaches the reader rather than being held as an empty pool`() = runTest {
        // Given - a source that outlives the job of the reader that opened it
        var calls = 0
        val names = ResolvedEntityNames(lookupScope)
        names.offerAliases {
            calls++
            delay(SOURCE_MS)
            POOL
        }
        val isolated = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))

        // When - that reader's job is cancelled mid-lookup, and a healthy reader asks after it
        val reader = isolated.async(names) { names.aliases() }
        delay(SOURCE_MS / 2)
        isolated.cancel()
        val thrown = runCatching { reader.await() }.exceptionOrNull()
        val recovered = withContext(names) { names.aliases() }

        // Then - cancellation propagated, and the lookup it opened still answered the next reader
        assertTrue("expected cancellation to propagate, got $thrown", thrown is CancellationException)
        assertEquals(POOL, recovered)
        assertEquals(1, calls)
    }

    private companion object {
        const val SOURCE_MS = 400L
        val POOL = listOf(AlternativeName("Tokyo Jihen", official = true))
    }
}
