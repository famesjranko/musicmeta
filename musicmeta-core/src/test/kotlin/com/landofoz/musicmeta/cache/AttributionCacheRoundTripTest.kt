package com.landofoz.musicmeta.cache

import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AttributionCacheRoundTripTest {
    private val type = EnrichmentType.ALBUM_ART
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test fun `memory cache preserves independent primary and alternative attribution`() = runTest {
        // Given - an artwork result with one separately attributed alternative file
        val cache = InMemoryEnrichmentCache()
        val expected = artwork()

        // When - the result is stored and read through the in-memory cache
        cache.put("album:memory", type, expected, CanonicalStatus.RESOLVED)
        val actual = requireNotNull(cache.get("album:memory", type)).result

        // Then - both file attributions survive unchanged
        assertEquals(expected.data, actual.data)
    }

    @Test fun `custom serializing cache preserves attributions on full partial and stale routes`() = runTest {
        // Given - an expired serialized cache value with two independently credited files
        val cache = JsonCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(request, type)
        val expected = artwork()
        cache.put(key, type, expected, CanonicalStatus.RESOLVED, ttlMs = 1)
        cache.now = 2
        val provider = FakeProvider("fresh", capabilities = listOf(ProviderCapability(type, 100))).also {
            it.givenResult(type, EnrichmentResult.Error(type, "fresh", "offline"))
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR),
        )

        // When - full and partial requests need stale substitution from the serializing cache
        val full = engine.enrich(request, setOf(type)).raw.getValue(type) as EnrichmentResult.Success
        val partial = engine.enrich(request, setOf(type, EnrichmentType.LABEL)).raw.getValue(type) as EnrichmentResult.Success

        // Then - both paths replay the primary and alternative file facts without cross-crediting
        assertArtworkAttribution(expected, full)
        assertArtworkAttribution(expected, partial)
        assertNotNull(cache.rawJson)
        engine.close()
    }

    private fun assertArtworkAttribution(expected: EnrichmentResult.Success, actual: EnrichmentResult.Success) {
        val expectedArtwork = expected.data as EnrichmentData.Artwork
        val actualArtwork = actual.data as EnrichmentData.Artwork
        assertEquals(expectedArtwork.attribution, actualArtwork.attribution)
        assertEquals(expectedArtwork.alternatives?.single()?.attribution, actualArtwork.alternatives?.single()?.attribution)
    }

    private fun artwork(): EnrichmentResult.Success = EnrichmentResult.Success(
        type,
        EnrichmentData.Artwork(
            url = "https://images.test/primary.jpg",
            attribution = ContentAttribution("File:primary.jpg", "https://files.test/primary"),
            alternatives = listOf(
                ArtworkSource(
                    provider = "alternative",
                    url = "https://images.test/alternative.jpg",
                    attribution = ContentAttribution("File:alternative.jpg", "https://files.test/alternative"),
                ),
            ),
        ),
        "primary",
        1f,
    )

    private class JsonCache : EnrichmentCache {
        var now = 0L
        var rawJson: String? = null
        private var value: Stored? = null

        override suspend fun get(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>? =
            value?.takeIf { it.key == entityKey && it.type == type && it.expiresAt > now }?.decode()

        override suspend fun getIncludingExpired(
            entityKey: String,
            type: EnrichmentType,
        ): CacheEnvelope<EnrichmentResult.Success>? = value?.takeIf { it.key == entityKey && it.type == type }?.decode()

        override suspend fun put(
            entityKey: String,
            type: EnrichmentType,
            result: EnrichmentResult.Success,
            canonicalStatus: CanonicalStatus,
            ttlMs: Long,
        ) {
            rawJson = json.encodeToString(EnrichmentData.serializer(), result.data)
            value = Stored(entityKey, type, rawJson!!, canonicalStatus, now + ttlMs)
        }

        override suspend fun getNegative(entityKey: String, type: EnrichmentType) = null
        override suspend fun putNegative(entityKey: String, type: EnrichmentType, result: EnrichmentResult.NotFound, canonicalStatus: CanonicalStatus, ttlMs: Long) = Unit
        override suspend fun invalidate(entityKey: String, type: EnrichmentType?) { value = null }
        override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) = false
        override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) = Unit
        override suspend fun clear() { value = null }

        private data class Stored(
            val key: String,
            val type: EnrichmentType,
            val dataJson: String,
            val canonicalStatus: CanonicalStatus,
            val expiresAt: Long,
        ) {
            fun decode(): CacheEnvelope<EnrichmentResult.Success> = CacheEnvelope(
                EnrichmentResult.Success(type, json.decodeFromString(EnrichmentData.serializer(), dataJson), "custom", 1f),
                canonicalStatus,
            )
        }

        private companion object {
            val json = Json { encodeDefaults = true }
        }
    }
}
