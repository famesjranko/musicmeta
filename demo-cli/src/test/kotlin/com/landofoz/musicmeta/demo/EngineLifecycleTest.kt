package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.demo.ui.Spinner
import com.landofoz.musicmeta.demo.ui.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who closes the engine, and when — the part a worked example is read for. */
class EngineLifecycleTest {

    private val built = mutableListOf<FakeEngine>()

    private fun stateWith(term: Terminal, engine: () -> FakeEngine = { FakeEngine() }): DemoState =
        DemoState(logger = DemoLogger(term), buildEngine = { engine().also { built += it } })

    @Test
    fun `rebuild closes the engine it replaces`() {
        // Given - a demo whose engine has been built once
        val output = captureOutput { term ->
            val state = stateWith(term)
            state.rebuild()

            // When - a config change rebuilds it
            state.rebuild()
        }

        // Then - the engine no longer reachable was closed, and the one now in use was not
        assertEquals(output, 2, built.size)
        assertTrue(built[0].closed)
        assertFalse(built[1].closed)
    }

    @Test
    fun `a session closes its engine on the way out`() {
        // Given - a demo running one command and exiting
        captureOutput { term ->
            val state = stateWith(term)
            state.rebuild()
            state.logger.enabled = true

            // When - the session ends normally
            runDemo(state, term, Spinner(term), arrayOf("artist", "Radiohead"))
        }

        // Then - the engine was closed rather than left holding its scope
        assertTrue(built.single().closed)
    }

    @Test
    fun `a session closes its engine even when the command throws`() {
        // Given - a demo whose engine fails the command it is given
        val boom = IllegalStateException("upstream exploded")
        var thrown: Throwable? = null

        captureOutput { term ->
            val state = stateWith(term) { FakeEngine(failWith = boom) }
            state.rebuild()
            state.logger.enabled = true

            // When - the command throws out of the session
            thrown = runCatching {
                runDemo(state, term, Spinner(term), arrayOf("artist", "Radiohead"))
            }.exceptionOrNull()
        }

        // Then - the failure still reaches the caller, and the engine was closed on the way past
        assertEquals(boom, thrown)
        assertTrue(built.single().closed)
    }
}
