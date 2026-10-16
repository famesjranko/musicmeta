package com.landofoz.musicmeta.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCommandTest {

    @Test
    fun `pin says the mark is a flag the caller reads and does not promise protection`() {
        // Given - an engine that accepts the pin
        val engine = FakeEngine()

        // When - pinning a type on an artist
        val output = captureOutput { term ->
            handlePin("artist Radiohead bio", DemoState(logger = DemoLogger(term)).also { it.engine = engine }, term)
        }

        // Then - the message marks the pin as advisory and does not claim later enrichments keep it
        assertTrue(output, output.contains("Marked"))
        assertTrue(output, output.contains("advisory"))
        assertFalse(output, output.contains("keep it"))
    }
}
