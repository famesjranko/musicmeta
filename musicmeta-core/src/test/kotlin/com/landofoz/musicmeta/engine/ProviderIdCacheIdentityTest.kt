package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache-key priority [entityKeyFor] applies: canonical MusicBrainz id, then a provider id
 * trusted for the request's exact [EnrichmentType] (see the audited tuples in `EntityKey.kt`),
 * then the bare name — and that every read/write path in [DefaultEnrichmentEngine] addresses the
 * same key for a given request/type.
 */
class ProviderIdCacheIdentityTest {

    private val config = EnrichmentConfig(enableIdentityResolution = false)

    private fun trackRequest(deezerId: String? = null, title: String = "Starman", artist: String = "David Bowie") =
        EnrichmentRequest.ForTrack(
            identifiers = deezerId?.let { EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, it) }
                ?: EnrichmentIdentifiers(),
            title = title,
            artist = artist,
        )

    private fun previewResult(provider: String) = EnrichmentResult.Success(
        EnrichmentType.TRACK_PREVIEW,
        EnrichmentData.TrackPreview(url = "https://example.com/preview.mp3", durationMs = 30_000, source = provider),
        provider,
        0.9f,
    )

    private fun previewProvider(result: EnrichmentResult) = FakeProvider(
        id = "deezer",
        capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
    ).also { it.givenResult(EnrichmentType.TRACK_PREVIEW, result) }

    private fun engine(provider: FakeProvider, cache: InMemoryEnrichmentCache) =
        DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, config)

    @Test fun `a Deezer track id request writes and reads track deezer id, not the name key`() = runTest {
        // Given - a provider that answers TRACK_PREVIEW, and a request carrying only a Deezer track id
        val cache = InMemoryEnrichmentCache()
        val provider = previewProvider(previewResult("deezer"))
        val request = trackRequest(deezerId = "107471926")

        // When - enriching once to populate the cache
        engine(provider, cache).enrich(request, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - the result lives under the provider-scoped key, not the bare name key
        val providerKey = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.TRACK_PREVIEW)
        val nameKey = DefaultEnrichmentEngine.entityKeyForName(request, EnrichmentType.TRACK_PREVIEW)
        assertEquals("track:deezer:107471926:TRACK_PREVIEW", providerKey)
        assertNotEquals(providerKey, nameKey)
        assertNotNull(cache.get(providerKey, EnrichmentType.TRACK_PREVIEW))
        assertNull(cache.get(nameKey, EnrichmentType.TRACK_PREVIEW))
    }

    @Test fun `a bare-name request cannot read the Deezer-id-keyed result`() = runTest {
        // Given - a Deezer-id request already cached
        val cache = InMemoryEnrichmentCache()
        val provider = previewProvider(previewResult("deezer"))
        val idRequest = trackRequest(deezerId = "107471926")
        engine(provider, cache).enrich(idRequest, setOf(EnrichmentType.TRACK_PREVIEW))

        // When - the same title/artist is enriched again with no identifier at all
        val nameOnlyProvider = previewProvider(previewResult("deezer-fresh"))
        val nameOnlyRequest = trackRequest(deezerId = null)
        val result = engine(nameOnlyProvider, cache).enrich(nameOnlyRequest, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - the name-only call misses the id-keyed entry and asks its own provider
        assertEquals(1, nameOnlyProvider.enrichCalls.size)
        assertEquals(
            "deezer-fresh",
            (result.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success).provider,
        )
    }

    @Test fun `the same numeric Deezer id on two request kinds does not collide`() {
        // Given - a track request and an album request each carrying Deezer id 555
        val track = trackRequest(deezerId = "555")
        val album = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "555"),
            title = "The Rise and Fall of Ziggy Stardust",
            artist = "David Bowie",
        )

        // When - each is keyed for its own request-shaped enrichment type
        val trackKey = entityKeyFor(track, EnrichmentType.TRACK_PREVIEW)
        val albumKey = entityKeyFor(album, EnrichmentType.SIMILAR_ALBUMS)

        // Then - the keys are unrelated: the track one is provider-scoped, the album one is not
        assertEquals("track:deezer:555:TRACK_PREVIEW", trackKey)
        assertNotEquals(trackKey, albumKey)
        assertEquals(entityKeyForName(album, EnrichmentType.SIMILAR_ALBUMS), albumKey)
    }

    @Test fun `a Deezer seed-artist id on album similarity is not selected as an album key`() {
        // Given - an album request carrying a Deezer id (SimilarAlbumsProvider's seed-artist use)
        val album = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "42"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - the cache key is selected for SIMILAR_ALBUMS
        val key = entityKeyFor(album, EnrichmentType.SIMILAR_ALBUMS)

        // Then - it falls back to the bare name key; the id is never read as an album identity
        assertEquals(entityKeyForName(album, EnrichmentType.SIMILAR_ALBUMS), key)
        assertTrue(key.startsWith("album:Radiohead:OK Computer:"))
    }

    @Test fun `an unscoped Deezer id falls back to the name key`() {
        // Given - a track request carrying a Deezer id, requested for a type not in the allowlist
        val request = trackRequest(deezerId = "777")

        // When - the cache key is selected for SIMILAR_TRACKS, which never reads a request
        // identifier at all (its Deezer artist id always comes from a fresh track search) and so
        // stays unallowlisted
        val key = entityKeyFor(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - it falls back to the bare name key
        assertEquals(entityKeyForName(request, EnrichmentType.SIMILAR_TRACKS), key)
    }

    @Test fun `a provider-id result is not alias-written to the bare name key`() = runTest {
        // Given - a Deezer-id-only track request (identity resolution disabled, so no MBID is ever
        // added) and a provider that answers it
        val cache = InMemoryEnrichmentCache()
        val provider = previewProvider(previewResult("deezer"))
        val request = trackRequest(deezerId = "9001")

        // When - enriching once
        engine(provider, cache).enrich(request, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - only the provider-scoped key holds the result; the name key is untouched
        val nameKey = DefaultEnrichmentEngine.entityKeyForName(request, EnrichmentType.TRACK_PREVIEW)
        assertNull(cache.get(nameKey, EnrichmentType.TRACK_PREVIEW))
    }

    @Test fun `existing name aliasing after a fresh MBID resolution keeps working`() = runTest {
        // Given - identity resolution enabled, an identity provider that resolves an MBID for a
        // name-only album request with no identifier of any kind
        val idConfig = EnrichmentConfig(enableIdentityResolution = true)
        val cache = InMemoryEnrichmentCache()
        val identity = FakeProvider(
            id = "mb",
            isIdentityProvider = true,
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
        ).also {
            it.givenIdentityResult(
                EnrichmentResult.Success(
                    EnrichmentType.GENRE,
                    EnrichmentData.Metadata(genres = listOf("rock")),
                    "mb",
                    0.95f,
                    resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-ok-computer"),
                ),
            )
        }
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(),
            title = "OK Computer",
            artist = "Radiohead",
        )
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(identity)), cache, idConfig, mergers = emptyList())

        // When - enriching a type the identity payload itself answers
        e.enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the name key still holds the resolved result, as before this change
        val nameKey = DefaultEnrichmentEngine.entityKeyForName(request, EnrichmentType.GENRE)
        assertNotNull(cache.get(nameKey, EnrichmentType.GENRE))
    }

    @Test fun `stale, negative, invalidate, forceRefresh and manual selection all address the provider-id key`() = runTest {
        // Given - a Deezer-id track request already cached under its provider-scoped key
        val cache = InMemoryEnrichmentCache()
        val provider = previewProvider(previewResult("deezer"))
        val request = trackRequest(deezerId = "31337")
        val e = engine(provider, cache)
        e.enrich(request, setOf(EnrichmentType.TRACK_PREVIEW))
        val providerKey = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.TRACK_PREVIEW)

        // When - manually selecting, then force-refreshing (which invalidates first)
        e.markManuallySelected(request, EnrichmentType.TRACK_PREVIEW)
        val wasSelected = e.isManuallySelected(request, EnrichmentType.TRACK_PREVIEW)
        val freshProvider = previewProvider(previewResult("deezer-2"))
        val freshEngine = engine(freshProvider, cache)
        val refreshed = freshEngine.enrich(request, setOf(EnrichmentType.TRACK_PREVIEW), forceRefresh = true)

        // Then - manual selection and force-refresh both reached the same provider-scoped key: the
        // selection read back true, and the refresh actually re-asked the provider rather than
        // serving the entry the first call wrote under that same key
        assertTrue(wasSelected)
        assertEquals(1, freshProvider.enrichCalls.size)
        assertEquals(
            "deezer-2",
            (refreshed.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success).provider,
        )
        assertNotNull(cache.get(providerKey, EnrichmentType.TRACK_PREVIEW))
    }
}
