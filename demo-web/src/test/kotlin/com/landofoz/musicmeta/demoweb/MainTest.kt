package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.KeyRequirement
import com.landofoz.musicmeta.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTest {

    @Test
    fun `KEY_SPECS covers exactly the catalog entries a key or token gates`() {
        // Given - every ProviderCatalog entry whose KeyRequirement is Required or Optional
        val keyedCatalogIds = ProviderCatalog.entries
            .filter { it.keyRequirement is KeyRequirement.Required || it.keyRequirement is KeyRequirement.Optional }
            .map { it.id }
            .toSet()

        // When - the demo-local KEY_SPECS catalog ids are read
        val specIds = KEY_SPECS.map { it.catalogId }.toSet()

        // Then - the two sets are exactly equal in both directions, so a new keyed provider can't be
        // silently missed and a stale spec can't linger
        assertEquals(keyedCatalogIds, specIds)
    }
}
