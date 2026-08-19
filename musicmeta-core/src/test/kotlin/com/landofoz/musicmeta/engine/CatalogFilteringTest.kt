package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogFilteringTest {

    private val req = EnrichmentRequest.forArtist("Radiohead")

    // --- Test helpers ---

    /** Records warnings so a test can assert on the ones the catalog guard emits. */
    private class RecordingLogger : EnrichmentLogger {
        val warnings = mutableListOf<String>()
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            warnings.add(message)
        }
    }

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
        logger: EnrichmentLogger = EnrichmentLogger.NoOp,
        cache: FakeEnrichmentCache = FakeEnrichmentCache(),
    ): DefaultEnrichmentEngine {
        val config = EnrichmentConfig(
            enableIdentityResolution = false,
            catalogProvider = catalogProvider,
            catalogFilterMode = mode,
        )
        return DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, config, logger)
    }

    // --- Test 1: AVAILABLE_ONLY removes unavailable SimilarArtist items ---

    @Test fun `AVAILABLE_ONLY removes unavailable SimilarArtist items`() = runTest {
        // Given - 3 artists where the middle one is unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i != 1, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - middle item removed, first and third remain
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, artists.size)
        assertEquals("Artist A", artists[0].name)
        assertEquals("Artist C", artists[1].name)
    }

    // --- Test 2: AVAILABLE_ONLY removes unavailable RadioTrack items ---

    @Test fun `AVAILABLE_ONLY removes unavailable RadioTrack items`() = runTest {
        // Given - 2 tracks where the first is unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i != 0, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_RADIO, 100)),
        ).also { it.givenResult(EnrichmentType.ARTIST_RADIO, radioTracks("Track 1", "Track 2")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.ARTIST_RADIO))

        // Then - first track removed, second remains
        val success = results.raw[EnrichmentType.ARTIST_RADIO] as EnrichmentResult.Success
        val tracks = (success.data as EnrichmentData.RadioPlaylist).tracks
        assertEquals(1, tracks.size)
        assertEquals("Track 2", tracks[0].title)
    }

    // --- Test 3: AVAILABLE_ONLY removes unavailable SimilarAlbum items ---

    @Test fun `AVAILABLE_ONLY removes unavailable SimilarAlbum items`() = runTest {
        // Given - 2 albums where the second is unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i != 1, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ALBUMS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ALBUMS, similarAlbums("Album X", "Album Y")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ALBUMS))

        // Then - second album removed, first remains
        val success = results.raw[EnrichmentType.SIMILAR_ALBUMS] as EnrichmentResult.Success
        val albums = (success.data as EnrichmentData.SimilarAlbums).albums
        assertEquals(1, albums.size)
        assertEquals("Album X", albums[0].title)
    }

    // --- Test 4: AVAILABLE_FIRST reorders preserving relative order within each group ---

    @Test fun `AVAILABLE_FIRST reorders so available items precede unavailable, preserving relative order`() = runTest {
        // Given - pattern [unavailable, available, unavailable, available]
        val fakeCatalog = CatalogProvider { queries ->
            queries.mapIndexed { i, _ ->
                CatalogMatch(available = i % 2 == 1, source = "test")
            }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("A0-unavail", "A1-avail", "A2-unavail", "A3-avail")) }

        // When - enriching with AVAILABLE_FIRST filtering
        val results = engine(provider, fakeCatalog, mode = CatalogFilterMode.AVAILABLE_FIRST)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - available items come first [A1-avail, A3-avail, A0-unavail, A2-unavail]
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
        // Given - catalog says everything unavailable, but mode is UNFILTERED
        val fakeCatalog = CatalogProvider { queries ->
            queries.map { CatalogMatch(available = false, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When - enriching with UNFILTERED mode
        val results = engine(provider, fakeCatalog, mode = CatalogFilterMode.UNFILTERED)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - all items returned unchanged
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, artists.size)
    }

    // --- Test 6: No CatalogProvider configured returns all items unchanged ---

    @Test fun `no CatalogProvider configured returns all items unchanged`() = runTest {
        // Given - no catalog provider
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When - enriching without a catalog provider configured
        val results = engine(provider, catalogProvider = null)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - items returned unchanged
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(2, artists.size)
    }

    // --- Test 7: Non-recommendation types are never passed to CatalogProvider ---

    @Test fun `non-recommendation type results are never passed to CatalogProvider`() = runTest {
        // Given - ALBUM_ART result, catalog says nothing is available
        var checkAvailabilityCalled = false
        val fakeCatalog = CatalogProvider { queries ->
            checkAvailabilityCalled = true
            queries.map { CatalogMatch(available = false, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, albumArt()) }

        // When - enriching for ALBUM_ART
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - ALBUM_ART result unchanged, checkAvailability never called
        assertFalse(checkAvailabilityCalled)
        val success = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertNotNull(success.data)
    }

    // --- Test 8: AVAILABLE_ONLY with all items available returns all unchanged ---

    @Test fun `AVAILABLE_ONLY with all items available returns all items unchanged`() = runTest {
        // Given - all items available
        val fakeCatalog = CatalogProvider { queries ->
            queries.map { CatalogMatch(available = true, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - all 3 items returned
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(3, artists.size)
    }

    // --- Test 9: AVAILABLE_ONLY with all items unavailable returns NotFound ---

    @Test fun `AVAILABLE_ONLY with all items unavailable returns NotFound`() = runTest {
        // Given - all items unavailable
        val fakeCatalog = CatalogProvider { queries ->
            queries.map { CatalogMatch(available = false, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - NotFound returned because all items filtered out
        assertTrue(results.raw[EnrichmentType.SIMILAR_ARTISTS] is EnrichmentResult.NotFound)
    }

    // --- Test 10: a throwing CatalogProvider degrades to unfiltered results ---

    @Test fun `AVAILABLE_ONLY degrades to unfiltered results when the CatalogProvider throws`() = runTest {
        // Given - a catalog provider that throws instead of answering, and a logger recording warnings
        val fakeCatalog = CatalogProvider { error("catalog unavailable") }
        val logger = RecordingLogger()
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog, logger = logger)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the unfiltered result survives instead of the throw reaching the caller
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), artists.map { it.name })

        // And - the degrade is logged
        val warning = logger.warnings.single { it.startsWith("SIMILAR_ARTISTS: ") }
        assertTrue(warning, warning.contains("catalog unavailable"))
    }

    @Test fun `a degraded-unfiltered result self-reports via isCatalogDegraded`() = runTest {
        // Given - a catalog provider that throws instead of answering
        val fakeCatalog = CatalogProvider { error("catalog unavailable") }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When - enriching with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the result names its own degradation, without the caller having to watch logs for it
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertTrue("expected isCatalogDegraded to be true", success.isCatalogDegraded)
    }

    @Test fun `UNFILTERED mode never reports isCatalogDegraded, even when the CatalogProvider would have thrown`() = runTest {
        // Given - a catalog provider that throws, but the mode is UNFILTERED so it is never called
        val fakeCatalog = CatalogProvider { error("catalog unavailable") }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }

        // When - enriching with UNFILTERED
        val results = engine(provider, fakeCatalog, CatalogFilterMode.UNFILTERED).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - UNFILTERED is a deliberate configuration, not a degradation
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertFalse("UNFILTERED must never self-report as degraded", success.isCatalogDegraded)
    }

    // --- isCatalogDegraded is call-scoped: recomputed live on every cache hit, never replayed ---

    @Test fun `a cache hit recomputes isCatalogDegraded true against a currently-throwing CatalogProvider, even though it was written healthy`() =
        runTest {
            // Given - a shared cache, first written by a call with a healthy catalog
            val cache = FakeEnrichmentCache()
            val provider = FakeProvider(
                id = "fake",
                capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
            ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }
            val healthyCatalog = CatalogProvider { queries -> queries.map { CatalogMatch(available = true, source = "test") } }
            val written = engine(provider, healthyCatalog, cache = cache).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))
            val writtenSuccess = written.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
            assertFalse("sanity: the original write must be healthy", writtenSuccess.isCatalogDegraded)

            // When - a second call against the same cache, now with a throwing CatalogProvider, serves the cache hit
            val throwingCatalog = CatalogProvider { error("catalog unavailable") }
            val second = engine(provider, throwingCatalog, cache = cache).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

            // Then - the cache-hit serve reports the *current* catalog's health, not the stored one
            val cachedSuccess = second.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
            assertTrue("a cache hit must recompute isCatalogDegraded against the live CatalogProvider", cachedSuccess.isCatalogDegraded)
        }

    @Test fun `a cache hit recomputes isCatalogDegraded false against a healthy CatalogProvider, even though it was written degraded`() =
        runTest {
            // Given - a shared cache, first written by a call whose CatalogProvider threw
            val cache = FakeEnrichmentCache()
            val provider = FakeProvider(
                id = "fake",
                capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
            ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B")) }
            val throwingCatalog = CatalogProvider { error("catalog unavailable") }
            val written = engine(provider, throwingCatalog, cache = cache).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))
            val writtenSuccess = written.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
            assertTrue("sanity: the original write must be degraded", writtenSuccess.isCatalogDegraded)

            // When - a second call against the same cache, now with a healthy CatalogProvider, serves the cache hit
            val healthyCatalog = CatalogProvider { queries -> queries.map { CatalogMatch(available = true, source = "test") } }
            val second = engine(provider, healthyCatalog, cache = cache).enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

            // Then - the cache-hit serve reports the *current* catalog's health, not the stored one
            val cachedSuccess = second.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
            assertFalse(
                "a cache hit must not replay a stale degrade once the CatalogProvider recovers",
                cachedSuccess.isCatalogDegraded,
            )
        }

    @Test fun `AVAILABLE_FIRST degrades to the original order when the CatalogProvider throws`() = runTest {
        // Given - a catalog provider that throws, and AVAILABLE_FIRST reordering requested
        val fakeCatalog = CatalogProvider { error("catalog unavailable") }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C")) }

        // When - enriching with AVAILABLE_FIRST filtering
        val results = engine(provider, fakeCatalog, CatalogFilterMode.AVAILABLE_FIRST)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the items keep the provider's order rather than the caller seeing an exception
        val success = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), artists.map { it.name })
    }

    @Test fun `a throw for one type still filters the type checked after it`() = runTest {
        // Given - a catalog that throws for the similar artists but answers for the radio tracks
        val fakeCatalog = CatalogProvider { queries ->
            if (queries.any { it.title.startsWith("Artist") }) error("catalog unavailable")
            queries.mapIndexed { i, _ -> CatalogMatch(available = i != 1, source = "test") }
        }
        val provider = FakeProvider(
            id = "fake",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100),
                ProviderCapability(EnrichmentType.ARTIST_RADIO, 100),
            ),
        ).also {
            it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B", "Artist C"))
            it.givenResult(EnrichmentType.ARTIST_RADIO, radioTracks("Track 1", "Track 2"))
        }

        // When - enriching both types with AVAILABLE_ONLY filtering
        val results = engine(provider, fakeCatalog)
            .enrich(req, setOf(EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.ARTIST_RADIO))

        // Then - the throwing type degrades to unfiltered and the type checked after it is filtered
        val artists = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals(
            listOf("Artist A", "Artist B", "Artist C"),
            (artists.data as EnrichmentData.SimilarArtists).artists.map { it.name },
        )
        val radio = results.raw[EnrichmentType.ARTIST_RADIO] as EnrichmentResult.Success
        assertEquals(listOf("Track 1"), (radio.data as EnrichmentData.RadioPlaylist).tracks.map { it.title })
    }
}
