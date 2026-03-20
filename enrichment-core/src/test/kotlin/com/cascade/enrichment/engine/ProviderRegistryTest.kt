package com.cascade.enrichment.engine

import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.ProviderCapability
import com.cascade.enrichment.testutil.FakeProvider
import org.junit.Assert.*
import org.junit.Test

class ProviderRegistryTest {
    @Test fun `chainFor returns chain for supported type`() {
        // Given — registry with one provider supporting ALBUM_ART
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val registry = ProviderRegistry(listOf(p))

        // When — requesting a chain for ALBUM_ART
        val chain = registry.chainFor(EnrichmentType.ALBUM_ART)

        // Then — a chain is returned
        assertNotNull(chain)
    }

    @Test fun `chainFor returns null for unsupported type`() {
        // Given — registry with one provider supporting only ALBUM_ART
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val registry = ProviderRegistry(listOf(p))

        // When — requesting a chain for LYRICS_SYNCED (not supported)
        val chain = registry.chainFor(EnrichmentType.LYRICS_SYNCED)

        // Then — returns null
        assertNull(chain)
    }

    @Test fun `supportedTypes returns all types with providers`() {
        // Given — registry with one provider supporting ALBUM_ART and GENRE
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100), ProviderCapability(EnrichmentType.GENRE, 80)))
        val registry = ProviderRegistry(listOf(p))

        // When — querying supported types
        val types = registry.supportedTypes()

        // Then — both types are reported
        assertEquals(setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE), types)
    }

    @Test fun `providerInfos returns all providers`() {
        // Given — registry with two providers, one requiring an API key
        val p1 = FakeProvider(id = "p1", requiresApiKey = false)
        val p2 = FakeProvider(id = "p2", requiresApiKey = true)
        val registry = ProviderRegistry(listOf(p1, p2))

        // When — listing provider infos
        val infos = registry.providerInfos()

        // Then — both providers listed with correct metadata
        assertEquals(2, infos.size)
        assertTrue(infos[1].requiresApiKey)
    }

    @Test fun `empty registry has no chains`() {
        // Given — registry with no providers
        val registry = ProviderRegistry(emptyList())

        // When — querying supported types
        val types = registry.supportedTypes()

        // Then — no types supported
        assertTrue(types.isEmpty())
    }
}
