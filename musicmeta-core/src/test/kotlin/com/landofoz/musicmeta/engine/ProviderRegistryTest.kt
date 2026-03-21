package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
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

    @Test fun `priorityOverrides changes chain ordering`() = runTest {
        // Given — two providers for ALBUM_ART: primary (100) and fallback (50)
        val primary = FakeProvider(id = "primary", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("primary.jpg"), "primary", 0.9f)) }
        val fallback = FakeProvider(id = "fallback", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("fallback.jpg"), "fallback", 0.9f)) }

        // When — override promotes fallback above primary
        val registry = ProviderRegistry(
            listOf(primary, fallback),
            priorityOverrides = mapOf("fallback" to mapOf(EnrichmentType.ALBUM_ART to 200)),
        )
        val result = registry.chainFor(EnrichmentType.ALBUM_ART)!!
            .resolve(EnrichmentRequest.forAlbum("Test", "Artist"))

        // Then — fallback provider wins because its overridden priority (200) > primary (100)
        assertEquals("fallback", (result as EnrichmentResult.Success).provider)
    }

    @Test fun `priorityOverrides does not affect non-matching types`() = runTest {
        // Given — provider with ALBUM_ART (100) and GENRE (80)
        val p = FakeProvider(id = "p", capabilities = listOf(
            ProviderCapability(EnrichmentType.ALBUM_ART, 100),
            ProviderCapability(EnrichmentType.GENRE, 80),
        ))

        // When — override only targets ALBUM_ART
        val registry = ProviderRegistry(
            listOf(p),
            priorityOverrides = mapOf("p" to mapOf(EnrichmentType.ALBUM_ART to 200)),
        )

        // Then — both chains exist (override didn't break anything)
        assertNotNull(registry.chainFor(EnrichmentType.ALBUM_ART))
        assertNotNull(registry.chainFor(EnrichmentType.GENRE))
    }
}
