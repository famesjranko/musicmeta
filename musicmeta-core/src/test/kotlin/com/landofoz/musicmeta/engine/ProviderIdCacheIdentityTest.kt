package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdCacheIdentityTest {

    private val config = EnrichmentConfig(enableIdentityResolution = false)

    @Test
    fun `primary and name keys share shape while identifiers distinguish every supported field`() {
        val base = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(),
            title = "Release: A",
            artist = "Artist|B",
            trackCount = 10,
            year = 2024,
        )
        val ids = base.copy(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "release-mbid",
                musicBrainzReleaseGroupId = "rg-1",
                wikidataId = "Q1",
                isrc = "US-X1",
                barcode = "012:345",
                wikipediaTitle = "A|B",
                extra = mapOf(
                    "itunesCollectionId" to "collection-1",
                    "discogsMasterId" to "master-1",
                    "discogsReleaseId" to "release-1",
                ),
            ),
        )
        val primary = entityKeyFor(ids, EnrichmentType.ALBUM_METADATA)
        val name = entityKeyForName(ids, EnrichmentType.ALBUM_METADATA)

        assertNotEquals(primary, name)
        assertEquals(name, entityKeyFor(base, EnrichmentType.ALBUM_METADATA))
        assertTrue(primary.startsWith("musicmeta-cache-key-v2"))
        assertTrue(primary.contains("id.musicBrainzReleaseGroupId"))
        assertTrue(primary.contains("id.extra.discogsMasterId"))
    }

    @Test
    fun `all exact album identifiers isolate releases and native provider scopes`() {
        val requests = listOf(
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(musicBrainzReleaseGroupId = "rg-1")),
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(wikidataId = "Q1")),
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(wikipediaTitle = "A")),
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(barcode = "barcode-1")),
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(extra = mapOf("itunesCollectionId" to "collection-1"))),
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(extra = mapOf("discogsReleaseId" to "release-1"))),
            EnrichmentRequest.forAlbum("A", "Artist", identifiers = EnrichmentIdentifiers(extra = mapOf("discogsMasterId" to "master-1"))),
        )

        requests.forEachIndexed { index, request ->
            requests.drop(index + 1).forEach { other ->
                assertNotEquals(entityKeyFor(request, EnrichmentType.ALBUM_METADATA), entityKeyFor(other, EnrichmentType.ALBUM_METADATA))
            }
        }
    }

    @Test
    fun `album year and track count are part of identity`() {
        val first = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "A", "Artist", trackCount = 10, year = 2020)
        val differentCount = first.copy(trackCount = 11)
        val differentYear = first.copy(year = 2021)
        assertNotEquals(entityKeyFor(first, EnrichmentType.ALBUM_TRACKS), entityKeyFor(differentCount, EnrichmentType.ALBUM_TRACKS))
        assertNotEquals(entityKeyFor(first, EnrichmentType.ALBUM_TRACKS), entityKeyFor(differentYear, EnrichmentType.ALBUM_TRACKS))
    }

    @Test
    fun `track album and duration are part of identity`() {
        val first = EnrichmentRequest.ForTrack(EnrichmentIdentifiers(), "Song", "Artist", "Album", 1000)
        assertNotEquals(
            entityKeyFor(first, EnrichmentType.TRACK_METADATA),
            entityKeyFor(first.copy(album = "Other"), EnrichmentType.TRACK_METADATA),
        )
        assertNotEquals(
            entityKeyFor(first, EnrichmentType.TRACK_METADATA),
            entityKeyFor(first.copy(durationMs = 1001), EnrichmentType.TRACK_METADATA),
        )
    }

    @Test
    fun `extras use deterministic sorted order and delimiter-safe values`() {
        val a = EnrichmentIdentifiers(extra = linkedMapOf("b" to "one", "a" to "two"))
        val b = EnrichmentIdentifiers(extra = linkedMapOf("a" to "two", "b" to "one"))
        assertEquals(
            entityKeyFor(EnrichmentRequest.forArtist("Artist", identifiers = a), EnrichmentType.ARTIST_BIO),
            entityKeyFor(EnrichmentRequest.forArtist("Artist", identifiers = b), EnrichmentType.ARTIST_BIO),
        )

        val first = EnrichmentRequest.forTrack("C", "A:B")
        val second = EnrichmentRequest.forTrack("B:C", "A")
        assertNotEquals(entityKeyForName(first, EnrichmentType.TRACK_METADATA), entityKeyForName(second, EnrichmentType.TRACK_METADATA))
    }

    @Test
    fun `mixed request ignores pre-existing single-id and name poison then replays its tuple`() = runTest {
        val cache = InMemoryEnrichmentCache()
        val mixed = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid").with(IdentifierNamespace.DEEZER, "deezer"),
            title = "Song",
            artist = "Artist",
        )
        val mbidOnly = mixed.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid"))
        val poison = previewResult("poison")
        cache.put(entityKeyFor(mbidOnly, EnrichmentType.TRACK_PREVIEW), EnrichmentType.TRACK_PREVIEW, poison, CanonicalStatus.RESOLVED)
        cache.put(entityKeyForName(mixed, EnrichmentType.TRACK_PREVIEW), EnrichmentType.TRACK_PREVIEW, poison, CanonicalStatus.RESOLVED)

        val provider = previewProvider(previewResult("fresh"))
        val engine = engine(provider, cache)
        val first = engine.enrich(mixed, setOf(EnrichmentType.TRACK_PREVIEW))
        val second = engine.enrich(mixed, setOf(EnrichmentType.TRACK_PREVIEW))

        assertEquals(1, provider.enrichCalls.size)
        assertEquals("fresh", (first.raw.getValue(EnrichmentType.TRACK_PREVIEW) as EnrichmentResult.Success).provider)
        assertEquals("fresh", (second.raw.getValue(EnrichmentType.TRACK_PREVIEW) as EnrichmentResult.Success).provider)
        assertNotNull(cache.get(entityKeyFor(mixed, EnrichmentType.TRACK_PREVIEW), EnrichmentType.TRACK_PREVIEW))
    }

    @Test
    fun `mixed negative cache, manual selection, invalidate, and force refresh use tuple`() = runTest {
        val cache = InMemoryEnrichmentCache()
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid").with(IdentifierNamespace.DEEZER, "deezer"),
            name = "Artist",
        )
        val notFoundProvider = FakeProvider(
            id = "empty",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_TOP_TRACKS, 100)),
        )
        val engine = engine(notFoundProvider, cache)
        engine.enrich(request, setOf(EnrichmentType.ARTIST_TOP_TRACKS))
        engine.enrich(request, setOf(EnrichmentType.ARTIST_TOP_TRACKS))
        assertEquals(1, notFoundProvider.enrichCalls.size)

        engine.markManuallySelected(request, EnrichmentType.ARTIST_TOP_TRACKS)
        assertTrue(engine.isManuallySelected(request, EnrichmentType.ARTIST_TOP_TRACKS))
        assertFalse(
            engine.isManuallySelected(
                request.copy(identifiers = EnrichmentIdentifiers(), name = request.name),
                EnrichmentType.ARTIST_TOP_TRACKS,
            ),
        )
        engine.invalidate(request, EnrichmentType.ARTIST_TOP_TRACKS)
        assertTrue(engine.isManuallySelected(request, EnrichmentType.ARTIST_TOP_TRACKS))

        val fresh = FakeProvider(
            id = "fresh",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_TOP_TRACKS, 100)),
        ).also { it.givenResult(EnrichmentType.ARTIST_TOP_TRACKS, topTracksResult("fresh")) }
        engine(fresh, cache).enrich(request, setOf(EnrichmentType.ARTIST_TOP_TRACKS), forceRefresh = true)
        assertEquals(1, fresh.enrichCalls.size)
        assertTrue(engine.isManuallySelected(request, EnrichmentType.ARTIST_TOP_TRACKS))
    }

    @Test
    fun `exact-bearing invalidation preserves a conflicting bare-name entry`() = runTest {
        val cache = InMemoryEnrichmentCache()
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "exact-a"),
            title = "Song",
            artist = "Artist",
        )
        val nameKey = entityKeyForName(request, EnrichmentType.TRACK_PREVIEW)
        cache.put(nameKey, EnrichmentType.TRACK_PREVIEW, previewResult("other-entity"), CanonicalStatus.RESOLVED)
        val engine = engine(
            FakeProvider(capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100))),
            cache,
        )

        engine.invalidate(request, EnrichmentType.TRACK_PREVIEW)
        assertNotNull(cache.get(nameKey, EnrichmentType.TRACK_PREVIEW))
        engine.enrich(request, setOf(EnrichmentType.TRACK_PREVIEW), forceRefresh = true)
        assertNotNull(cache.get(nameKey, EnrichmentType.TRACK_PREVIEW))
    }

    @Test
    fun `Deezer discography is not treated as a native-id route`() {
        val request = EnrichmentRequest.forArtist(
            "Artist",
            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "deezer"),
        )
        val route = requireNotNull(trustedProviderIdentifier(request, EnrichmentType.ARTIST_TOP_TRACKS))
        assertEquals(EntityScope.ARTIST, route.scope)
        assertNull(trustedProviderIdentifier(request, EnrichmentType.ARTIST_DISCOGRAPHY))
    }

    private fun engine(provider: FakeProvider, cache: InMemoryEnrichmentCache) =
        DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, config)

    private fun previewResult(provider: String) = EnrichmentResult.Success(
        EnrichmentType.TRACK_PREVIEW,
        EnrichmentData.TrackPreview(url = "https://example.com/$provider.mp3", durationMs = 30_000, source = provider),
        provider,
        0.9f,
    )

    private fun previewProvider(result: EnrichmentResult) = FakeProvider(
        id = "deezer",
        capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
    ).also { it.givenResult(EnrichmentType.TRACK_PREVIEW, result) }

    private fun topTracksResult(provider: String) = EnrichmentResult.Success(
        EnrichmentType.ARTIST_TOP_TRACKS,
        EnrichmentData.TopTracks(emptyList()),
        provider,
        0.9f,
    )
}
