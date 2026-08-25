package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `close()`'s per-type completeness rule for a call the engine had never seen before shutdown:
 * every requested type is present, a settled type keeps its real result, and only an unsettled one
 * becomes `Error(ErrorKind.ENGINE_CLOSED)`. A partially cached call is where "settled" and
 * "unsettled" both appear in one snapshot — the cached types were settled by the cache read that
 * `enrichProgressive` performs before it ever consults the registry.
 */
// InjectDispatcher: a real dispatcher, matching the other close() tests — close() bridges through
// runBlocking, which virtual time cannot represent honestly.
@Suppress("InjectDispatcher")
class PostCloseCachedTypeTest {
    private val req = EnrichmentRequest.forArtist("Portishead")

    @Test
    fun `a partially cached call after close keeps the cached type's real result`() =
        runBlocking(Dispatchers.Default) {
            // Given - an engine that answered ARTIST_BIO once, so the cache holds it, while
            // ALBUM_ART for the same request has never been fetched or cached
            val bio = EnrichmentResult.Success(
                EnrichmentType.ARTIST_BIO,
                EnrichmentData.Biography("Formed in Bristol in 1991.", "bio"),
                "bio",
                0.9f,
            )
            val bioProvider = FakeProvider(
                id = "bio",
                capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
            ).apply { givenResult(EnrichmentType.ARTIST_BIO, bio) }
            val artProvider = FakeProvider(
                id = "art",
                capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            )
            val cache = FakeEnrichmentCache()
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(bioProvider, artProvider)),
                cache,
                EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 60_000),
            )
            engine.enrich(req, setOf(EnrichmentType.ARTIST_BIO))
            assertTrue("the warm-up call must have cached ARTIST_BIO", cache.stored.isNotEmpty())

            // When - the engine closes, then a request/types key it never saw asks for the cached
            // type alongside the uncached one
            engine.close()
            val afterClose = withTimeout(5_000) {
                engine.enrich(req, setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ALBUM_ART))
            }

            // Then - only the uncached type is stamped ENGINE_CLOSED, and the cached type comes
            // back as the Success the cache already held
            val albumArt = afterClose.raw[EnrichmentType.ALBUM_ART]
            assertTrue("the uncached type must be stamped Error, not omitted", albumArt is EnrichmentResult.Error)
            assertEquals(ErrorKind.ENGINE_CLOSED, (albumArt as EnrichmentResult.Error).errorKind)
            val artistBio = afterClose.raw[EnrichmentType.ARTIST_BIO]
            assertTrue(
                "close() promises a settled type keeps its real result, got $artistBio",
                artistBio is EnrichmentResult.Success,
            )
        }
}
