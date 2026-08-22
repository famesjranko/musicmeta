package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.engine.DEFAULT_SYNTHESIZERS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins demo-web's hand-written [DERIVED_FROM] against core's actual synthesizer registry. A card for
 * a synthesized type is credited by reading the upstreams of the types it was derived from; that
 * derivation lives in core's [DEFAULT_SYNTHESIZERS], and this table mirrors it by hand. If core adds
 * or re-wires a synthesizer, the mirror goes stale and the card credits the wrong providers with
 * nothing to catch it — this test is that catch.
 */
class SynthesizerDriftTest {
    @Test
    fun `DERIVED_FROM mirrors core's default synthesizer graph`() {
        // Given - core's registered default synthesizers, the source of truth
        val coreGraph = DEFAULT_SYNTHESIZERS.associate { it.type to it.dependencies }

        // When - demo-web's hand table is compared against it
        // Then - it matches type-for-type and dependency-for-dependency
        assertEquals(coreGraph, DERIVED_FROM)
    }
}
