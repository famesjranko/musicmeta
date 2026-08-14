package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.TimelineEvent
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompositeCacheIdentityTest {

    @Test
    fun `timeline with a mixed discography identity does not replay or overwrite cache`() = runTest {
        // Given - a timeline cache entry keyed by an MBID and a request whose discography can use a native id
        val cache = InMemoryEnrichmentCache()
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")
                .with(IdentifierNamespace.DEEZER, "foreign-deezer"),
            name = "Radiohead",
        )
        val key = DefaultEnrichmentEngine.entityKeyFor(
            request.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")),
            EnrichmentType.ARTIST_TIMELINE,
        )
        val poison = EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_TIMELINE,
            data = EnrichmentData.ArtistTimeline(listOf(TimelineEvent("1900", "formed", "poison"))),
            provider = "poison",
            confidence = 1f,
        )
        cache.put(key, EnrichmentType.ARTIST_TIMELINE, poison, CanonicalStatus.RESOLVED, 60_000)
        val discography = FakeProvider(
            id = "discography",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.ARTIST_DISCOGRAPHY,
                EnrichmentResult.Success(
                    EnrichmentType.ARTIST_DISCOGRAPHY,
                    EnrichmentData.Discography(listOf(DiscographyAlbum("Fresh", "2026"))),
                    "discography",
                    1f,
                ),
            )
        }
        val members = FakeProvider(
            id = "members",
            capabilities = listOf(ProviderCapability(EnrichmentType.BAND_MEMBERS, 100)),
        )

        // When - requesting the composite with identity resolution disabled
        val result = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(discography, members)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(request, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - the dependency was resolved and the mixed request did not read the single-id entry
        assertEquals(1, discography.enrichCalls.size)
        assertEquals("timeline_synthesizer", (result.raw.getValue(EnrichmentType.ARTIST_TIMELINE) as EnrichmentResult.Success).provider)
        assertEquals("poison", (cache.get(key, EnrichmentType.ARTIST_TIMELINE)!!.result as EnrichmentResult.Success).provider)
        assertNotEquals(key, DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.ARTIST_TIMELINE))
    }

    @Test
    fun `mixed Deezer artist discography does not replay or overwrite cache`() = runTest {
        // Given - a valid discography cached under the mixed request's MBID key
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")
                .with(IdentifierNamespace.DEEZER, "deezer-id"),
            name = "Radiohead",
        )
        assertMixedDiscographyIsolated(request)
    }

    @Test
    fun `mixed iTunes artist discography does not replay or overwrite cache`() = runTest {
        // Given - a valid discography cached under the mixed request's MBID key
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")
                .with(IdentifierNamespace.ITUNES_ARTIST, "itunes-id"),
            name = "Radiohead",
        )
        assertMixedDiscographyIsolated(request)
    }

    @Test
    fun `two native artist routes do not share an artist discography cache entry`() = runTest {
        // Given - a request carrying both native exact routes, with no canonical MBID
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers()
                .with(IdentifierNamespace.DEEZER, "deezer-id")
                .with(IdentifierNamespace.ITUNES_ARTIST, "itunes-id"),
            name = "Radiohead",
        )

        // When - enriching the request
        assertMixedDiscographyIsolated(request)

        // Then - the complete request tuple remains a replayable cache identity
        assertNotEquals(entityKeyForName(request, EnrichmentType.ARTIST_DISCOGRAPHY), entityKeyFor(request, EnrichmentType.ARTIST_DISCOGRAPHY))
    }

    @Test
    fun `mixed artist discography does not replay or overwrite a negative cache entry`() = runTest {
        // Given - a negative entry under an MBID key and a mixed native-id request
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")
                .with(IdentifierNamespace.DEEZER, "deezer-id"),
            name = "Radiohead",
        )
        val cache = InMemoryEnrichmentCache()
        val key = entityKeyFor(
            request.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")),
            EnrichmentType.ARTIST_DISCOGRAPHY,
        )
        val poison = EnrichmentResult.NotFound(EnrichmentType.ARTIST_DISCOGRAPHY, "poison")
        cache.putNegative(key, EnrichmentType.ARTIST_DISCOGRAPHY, poison, CanonicalStatus.RESOLVED, 60_000)
        val provider = FakeProvider(
            id = "fresh",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
        )

        // When - enriching the mixed request
        val result = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(request, setOf(EnrichmentType.ARTIST_DISCOGRAPHY))

        // Then - the provider was asked rather than accepting the single-id negative as proof
        assertEquals(1, provider.enrichCalls.size)
        assertEquals("poison", (cache.getNegative(key, EnrichmentType.ARTIST_DISCOGRAPHY)!!.result as EnrichmentResult.NotFound).provider)
        assertEquals("all_providers", (result.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.NotFound).provider)
        val replay = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(request, setOf(EnrichmentType.ARTIST_DISCOGRAPHY))
        assertEquals(1, provider.enrichCalls.size)
        assertEquals("all_providers", (replay.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `mixed artist discography does not attach selection or invalidate an arbitrary cache key`() = runTest {
        // Given - a mixed request and a valid entry under the canonical-looking key
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")
                .with(IdentifierNamespace.DEEZER, "deezer-id"),
            name = "Radiohead",
        )
        val cache = InMemoryEnrichmentCache()
        val key = entityKeyFor(
            request.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")),
            EnrichmentType.ARTIST_DISCOGRAPHY,
        )
        val cached = EnrichmentResult.Success(
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentData.Discography(listOf(DiscographyAlbum("Cached", "1997"))),
            "cached",
            1f,
        )
        cache.put(key, EnrichmentType.ARTIST_DISCOGRAPHY, cached, CanonicalStatus.RESOLVED, 60_000)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(emptyList()),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - asking for manual selection and invalidation on the mixed request
        engine.markManuallySelected(request, EnrichmentType.ARTIST_DISCOGRAPHY)
        val selected = engine.isManuallySelected(request, EnrichmentType.ARTIST_DISCOGRAPHY)
        engine.invalidate(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - selection and invalidation address the complete tuple, not the single-id entry
        assertEquals(true, selected)
        assertEquals("cached", (cache.get(key, EnrichmentType.ARTIST_DISCOGRAPHY)!!.result as EnrichmentResult.Success).provider)
    }

    private suspend fun assertMixedDiscographyIsolated(request: EnrichmentRequest.ForArtist) {
        val cache = InMemoryEnrichmentCache()
        val key = entityKeyFor(
            request.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid")),
            EnrichmentType.ARTIST_DISCOGRAPHY,
        )
        val poison = EnrichmentResult.Success(
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentData.Discography(listOf(DiscographyAlbum("Poison", "1900"))),
            "poison",
            1f,
        )
        cache.put(key, EnrichmentType.ARTIST_DISCOGRAPHY, poison, CanonicalStatus.RESOLVED, 60_000)
        val provider = FakeProvider(
            id = "fresh",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.ARTIST_DISCOGRAPHY,
                EnrichmentResult.Success(
                    EnrichmentType.ARTIST_DISCOGRAPHY,
                    EnrichmentData.Discography(listOf(DiscographyAlbum("Fresh", "2026"))),
                    "fresh",
                    1f,
                ),
            )
        }

        // When - enriching the mixed request
        val result = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(request, setOf(EnrichmentType.ARTIST_DISCOGRAPHY))

        // Then - the provider ran and the foreign cache entry was not read
        assertEquals(1, provider.enrichCalls.size)
        assertEquals("fresh", (result.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.Success).provider)
        assertEquals("poison", (cache.get(key, EnrichmentType.ARTIST_DISCOGRAPHY)!!.result as EnrichmentResult.Success).provider)
        val replay = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(request, setOf(EnrichmentType.ARTIST_DISCOGRAPHY))
        assertEquals(1, provider.enrichCalls.size)
        assertEquals("fresh", (replay.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.Success).provider)
    }

    @Test
    fun `name tuples containing a delimiter receive distinct cache keys`() {
        // Given - two legal track name tuples containing delimiter characters
        val first = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(),
            artist = "A:B",
            title = "C",
        )
        val second = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(),
            artist = "A",
            title = "B:C",
        )

        // When - deriving their name-only keys
        val firstKey = entityKeyForName(first, EnrichmentType.TRACK_METADATA)
        val secondKey = entityKeyForName(second, EnrichmentType.TRACK_METADATA)

        // Then - the keys remain stable and cannot alias distinct tuples
        assertNotEquals(firstKey, secondKey)
        assertNull(firstKey.takeIf { it == secondKey })
    }
}
