package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fan-out is one concurrent pass, and each type settles on its own (`ARCHITECTURE.md`, "One `enrich()` call"). */
class FanOutAndSettleTest {
    private val req = EnrichmentRequest.forArtist("Radiohead")

    private class GatedProvider(type: EnrichmentType, private val gate: CompletableDeferred<Unit>) :
        FakeProvider(id = "slow", capabilities = listOf(ProviderCapability(type, 100))) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            gate.await()
            return EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/photo.jpg"), "slow", 0.9f)
        }
    }

    @Test fun `a mergeable type settles while a regular type is still running`() = runBlocking {
        // Given - GENRE has a merger and answers at once, ARTIST_PHOTO is regular and waits on a gate
        val gate = CompletableDeferred<Unit>()
        val fast = FakeProvider(id = "fast", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.GENRE,
                    EnrichmentResult.Success(
                        EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "fast", 0.95f,
                    ),
                )
            }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(fast, GatedProvider(EnrichmentType.ARTIST_PHOTO, gate))),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )

        try {
            // When - collecting until the mergeable type appears, the regular type still gated
            val snapshot = withTimeout(5_000) {
                engine.enrichProgressive(req, setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO))
                    .first { EnrichmentType.GENRE in it.raw }
            }

            // Then - the mergeable type is already a Success and the regular type has not settled
            assertTrue(snapshot.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
            assertFalse(EnrichmentType.ARTIST_PHOTO in snapshot.raw)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test fun `a wikipedia biography with no attribution settles as a stamped fresh Success`() = runBlocking {
        // Given - a wikipedia Success for ARTIST_BIO, a stale-capable cache holding an older entry,
        // and a catalog filter configured
        val cache = FakeEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ARTIST_BIO)
        cache.expiredStore["$key:${EnrichmentType.ARTIST_BIO}"] = EnrichmentResult.Success(
            EnrichmentType.ARTIST_BIO, EnrichmentData.Biography("old text", "wikipedia"), "wikipedia", 0.9f,
        )
        val provider = FakeProvider(
            id = "wikipedia", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.ARTIST_BIO,
                EnrichmentResult.Success(
                    EnrichmentType.ARTIST_BIO, EnrichmentData.Biography("fresh text", "wikipedia"), "wikipedia", 0.9f,
                ),
            )
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR),
        )

        // When - enriching the biography
        val result = engine.enrich(req, setOf(EnrichmentType.ARTIST_BIO)).raw[EnrichmentType.ARTIST_BIO]

        // Then - the live Success survives settlement: provenance stamped, not replaced by the stale entry
        val success = result as EnrichmentResult.Success
        assertEquals("fresh text", (success.data as EnrichmentData.Biography).text)
        assertNotNull("provenance must be stamped", success.provenance)
        assertFalse("a live Success must not be stale-substituted", success.isStale)
    }
}
