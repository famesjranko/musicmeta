package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogFilteringTest {

    private val req = EnrichmentRequest.forArtist("Radiohead")

    // --- Test helpers ---

    private fun similarArtists(vararg names: String): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(
                artists = names.map { name ->
                    SimilarArtist(name = name, matchScore = 0.9f)
                }
            ),
            provider = "fake",
            confidence = 0.9f,
        )

    private fun radioTracks(vararg titles: String): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_RADIO,
            data = EnrichmentData.RadioPlaylist(
                tracks = titles.map { title ->
                    RadioTrack(title = title, artist = "Various")
                }
            ),
            provider = "fake",
            confidence = 0.9f,
        )

    private fun similarAlbums(vararg titles: String): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ALBUMS,
            data = EnrichmentData.SimilarAlbums(
                albums = titles.map { title ->
                    SimilarAlbum(title = title, artist = "Various", artistMatchScore = 0.8f)
                }
            ),
            provider = "fake",
            confidence = 0.9f,
        )

    private fun similarTracks(vararg titles: String): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(
                tracks = titles.map { title ->
                    SimilarTrack(title = title, artist = "Various", matchScore = 0.8f)
                }
            ),
            provider = "fake",
            confidence = 0.9f,
        )

    private fun albumArt(): EnrichmentResult.Success =
        EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "fake",
            confidence = 0.9f,
        )

    private fun engine(
        provider: FakeProvider,
        catalogProvider: CatalogProvider?,
        mode: CatalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
    ): DefaultEnrichmentEngine {
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            catalogProvider = catalogProvider,
            catalogFilterMode = mode,
        )
        return DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), FakeEnrichmentCache(), FakeHttpClient(), config)
    }

    // --- Test 1: AVAILABLE_ONLY removes unavailable SimilarArtist items ---

    @Test fun `AVAILABLE_ONLY removes unavailable SimilarArtist items`() = runTest {
        // Given — 3 artists where the middle one is unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i != 1, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C")) }

        // When
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — middle item removed, first and third remain
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, artists.size)
        assertEquals("Artist A", artists[0].name)
        assertEquals("Artist C", artists[1].name)
    }

    // --- Test 2: AVAILABLE_ONLY removes unavailable RadioTrack items ---

    @Test fun `AVAILABLE_ONLY removes unavailable RadioTrack items`() = runTest {
        // Given — 2 tracks where the first is unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i != 0, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_RADIO, 100)),
        ).also { it.givenResult(EnrichmentType.ARTIST_RADIO, radioTracks("Track 1", "Track 2")) }

        // When
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.ARTIST_RADIO))

        // Then — first track removed, second remains
        val success = results.raw[EnrichmentType.ARTIST_RADIO] as EnrichmentResult.Success
        val tracks = (success.data as EnrichmentData.RadioPlaylist).tracks
        assertEquals(1, tracks.size)
        assertEquals("Track 2", tracks[0].title)
    }

    // --- Test 3: AVAILABLE_ONLY removes unavailable SimilarAlbum items ---

    @Test fun `AVAILABLE_ONLY removes unavailable SimilarAlbum items`() = runTest {
        // Given — 2 albums where the second is unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i != 1, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ALBUMS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ALBUMS, similarAlbums("Album X", "Album Y")) }

        // When
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ALBUMS))

        // Then — second album removed, first remains
        val success = results.raw[EnrichmentType.SIMILAR_ALBUMS] as EnrichmentResult.Success
        val albums = (success.data as EnrichmentData.SimilarAlbums).albums
        assertEquals(1, albums.size)
        assertEquals("Album X", albums[0].title)
    }

    // --- Test 4: AVAILABLE_FIRST reorders preserving relative order within each group ---

    @Test fun `AVAILABLE_FIRST reorders so available items precede unavailable, preserving relative order`() = runTest {
        // Given — pattern [unavailable, available, unavailable, available]
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i % 2 == 1, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("A0-unavail", "A1-avail", "A2-unavail", "A3-avail")) }

        // When
        val results = engine(provider, fakeCatalog, mode = CatalogFilterMode.AVAILABLE_FIRST)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — available items come first [A1-avail, A3-avail, A0-unavail, A2-unavail]
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(4, artists.size)
        assertEquals("A1-avail", artists[0].name)
        assertEquals("A3-avail", artists[1].name)
        assertEquals("A0-unavail", artists[2].name)
        assertEquals("A2-unavail", artists[3].name)
    }

    // --- Test 5: UNFILTERED mode returns all items unchanged ---

    @Test fun `UNFILTERED mode returns all items unchanged even when catalog provider returns unavailable`() = runTest {
        // Given — catalog says everything unavailable, but mode is UNFILTERED
        val fakeCatalog = CatalogProvider { queries ->
            queries.map { CatalogMatch(available = false, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When
        val results = engine(provider, fakeCatalog, mode = CatalogFilterMode.UNFILTERED)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — all items returned unchanged
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, artists.size)
    }

    // --- Test 6: No CatalogProvider configured returns all items unchanged ---

    @Test fun `no CatalogProvider configured returns all items unchanged`() = runTest {
        // Given — no catalog provider
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When
        val results = engine(provider, catalogProvider = null)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — items returned unchanged
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, artists.size)
    }

    // --- Test 7: Non-recommendation types are never passed to CatalogProvider ---

    @Test fun `non-recommendation type results are never passed to CatalogProvider`() = runTest {
        // Given — ALBUM_ART result, catalog says nothing is available
        var checkAvailabilityCalled = false
        val fakeCatalog = CatalogProvider { queries ->
            checkAvailabilityCalled = true
            queries.map { CatalogMatch(available = false, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, albumArt()) }

        // When
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — ALBUM_ART result unchanged, checkAvailability never called
        assertFalse(checkAvailabilityCalled)
        val success = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertNotNull(success.data)
    }

    // --- Test 8: AVAILABLE_ONLY with all items available returns all unchanged ---

    @Test fun `AVAILABLE_ONLY with all items available returns all items unchanged`() = runTest {
        // Given — all items available
        val fakeCatalog = CatalogProvider { queries ->
            queries.map { CatalogMatch(available = true, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C")) }

        // When
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — all 3 items returned
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(3, artists.size)
    }

    // --- Test 9: AVAILABLE_ONLY with all items unavailable returns NotFound ---

    @Test fun `AVAILABLE_ONLY with all items unavailable returns NotFound`() = runTest {
        // Given — all items unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.map { CatalogMatch(available = false, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — NotFound returned because all items filtered out
        assertTrue(results.raw[EnrichmentType.SIMILAR_ARTISTS] is EnrichmentResult.NotFound)
    }
}
