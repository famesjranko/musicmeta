package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manual selection is advisory: it is a flag a caller reads, and no engine write or shipped
 * cache honours it. These tests pin that contract so the KDoc cannot drift back into a promise.
 */
class ManualSelectionAdvisoryTest {

    private val request = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
        name = "Radiohead",
    )

    private fun bio(text: String, provider: String, thumbnailUrl: String? = null) = EnrichmentResult.Success(
        type = EnrichmentType.ARTIST_BIO,
        data = EnrichmentData.Biography(text = text, source = provider, thumbnailUrl = thumbnailUrl),
        provider = provider,
        confidence = 1f,
    )

    private fun bioText(result: EnrichmentResult?): String =
        ((result as EnrichmentResult.Success).data as EnrichmentData.Biography).text

    @Test
    fun `an expired manual selection is replaced by the next enrichment and stays marked`() = runTest {
        // Given - a pinned biography whose entry has expired, and a provider now returning a new one
        var now = 1_000_000L
        val cache = InMemoryEnrichmentCache(clock = { now })
        val provider = FakeProvider(
            id = "bio",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
        ).also { it.givenResult(EnrichmentType.ARTIST_BIO, bio("old", "bio")) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false, ttlOverrides = mapOf(EnrichmentType.ARTIST_BIO to 1_000L)),
        )
        engine.enrich(request, setOf(EnrichmentType.ARTIST_BIO))
        engine.markManuallySelected(request, EnrichmentType.ARTIST_BIO)
        now += 5_000L
        provider.givenResult(EnrichmentType.ARTIST_BIO, bio("new", "bio"))

        // When - enriching again after the pinned entry expired
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_BIO))

        // Then - the new value is returned and cached, and the selection flag is still set
        assertEquals("new", bioText(result.raw[EnrichmentType.ARTIST_BIO]))
        val key = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.ARTIST_BIO)
        assertEquals("new", bioText(cache.get(key, EnrichmentType.ARTIST_BIO)?.result))
        assertTrue(engine.isManuallySelected(request, EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `an unexpired manual selection from wikipedia without attribution is served unchanged`() = runTest {
        // Given - a pinned, unexpired Wikipedia biography with a thumbnail and Wikimedia artwork,
        // neither carrying any attribution
        val cache = InMemoryEnrichmentCache()
        val cachedBio = bio("From Wikipedia", "wikipedia", thumbnailUrl = "https://upload.wikimedia.org/thumb.jpg")
        val cachedPhoto = EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_PHOTO,
            data = EnrichmentData.Artwork(url = "https://upload.wikimedia.org/photo.jpg"),
            provider = "wikipedia",
            confidence = 1f,
        )
        for (cached in listOf(cachedBio, cachedPhoto)) {
            val key = DefaultEnrichmentEngine.entityKeyFor(request, cached.type)
            cache.put(key, cached.type, cached, CanonicalStatus.RESOLVED, 60_000L)
            cache.markManuallySelected(key, cached.type)
        }
        val provider = FakeProvider(
            id = "other",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ARTIST_BIO, 100),
                ProviderCapability(EnrichmentType.ARTIST_PHOTO, 100),
            ),
        )
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - enriching both types
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO))

        // Then - both cached results come back as stored and no provider is asked
        assertEquals(cachedBio.data, (result.raw.getValue(EnrichmentType.ARTIST_BIO) as EnrichmentResult.Success).data)
        assertEquals(cachedPhoto.data, (result.raw.getValue(EnrichmentType.ARTIST_PHOTO) as EnrichmentResult.Success).data)
        assertTrue(provider.enrichCalls.isEmpty())
    }
}
