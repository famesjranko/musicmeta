package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedCacheFailureTest {
    @Test fun `a failed pin-state read fetches but does not persist`() = runTest {
        // Given - a custom cache whose manual-selection state cannot be read
        val backing = InMemoryEnrichmentCache()
        val cache = object : EnrichmentCache by backing {
            override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean =
                error("selection store unavailable")
        }
        val type = EnrichmentType.ALBUM_ART
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val provider = FakeProvider("provider", capabilities = listOf(ProviderCapability(type, 100)))
            .also {
                it.givenResult(
                    type,
                    EnrichmentResult.Success(
                        type,
                        EnrichmentData.Artwork("https://example.test/fresh.jpg"),
                        "provider",
                        0.9f,
                    ),
                )
            }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - enrichment receives a fresh provider answer
        engine.enrich(request, setOf(type))

        // Then - unreadable pin state never grants permission to write the custom cache
        assertNull(backing.get(DefaultEnrichmentEngine.entityKeyFor(request, type), type))
    }
}
