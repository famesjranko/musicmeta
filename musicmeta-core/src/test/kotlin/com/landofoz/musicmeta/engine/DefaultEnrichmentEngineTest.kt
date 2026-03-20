package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DefaultEnrichmentEngineTest {
    private lateinit var cache: FakeEnrichmentCache
    private val config = EnrichmentConfig(enableIdentityResolution = false)
    private val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Before fun setup() { cache = FakeEnrichmentCache() }

    private fun art(p: String) = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x.com/art.jpg"), p, 0.95f)
    private fun genre(p: String) = EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), p, 0.9f)

    private fun engine(vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, FakeHttpClient(), config)

    @Test fun `enrich returns cached result`() = runTest {
        // Given — cache pre-populated with an art result
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("cached"))

        // When — enriching with a provider that would return different data
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — cached result returned, provider never called
        assertEquals("cached", (results[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
        assertEquals(0, p.enrichCalls.size)
    }

    @Test fun `enrich fans out to provider chains`() = runTest {
        // Given — two providers, each handling a different type
        val p1 = FakeProvider(id = "art", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("art")) }
        val p2 = FakeProvider(id = "genre", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))).also { it.givenResult(EnrichmentType.GENRE, genre("genre")) }

        // When — requesting both types
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then — both resolved in parallel
        assertEquals(2, results.size)
        assertTrue(results[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(results[EnrichmentType.GENRE] is EnrichmentResult.Success)
    }

    @Test fun `enrich caches successful results`() = runTest {
        // Given — provider returns successful art result
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }

        // When — enriching
        engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — result persisted to cache
        assertFalse(cache.stored.isEmpty())
    }

    @Test fun `enrich does not cache errors`() = runTest {
        // Given — provider returns an error
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "err")) }

        // When — enriching
        engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — nothing cached
        assertTrue(cache.stored.isEmpty())
    }

    @Test fun `enrich returns partial results`() = runTest {
        // Given — art provider exists but no lyrics provider
        val p = FakeProvider(id = "art", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("art")) }

        // When — requesting art + lyrics
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.LYRICS_SYNCED))

        // Then — art succeeds, lyrics returns NotFound
        assertTrue(results[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(results[EnrichmentType.LYRICS_SYNCED] is EnrichmentResult.NotFound)
    }

    @Test fun `enrich filters below min confidence`() = runTest {
        // Given — provider returns result with 0.2 confidence (below 0.5 threshold)
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "p", 0.2f)) }

        // When — enriching
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — treated as NotFound
        assertTrue(results[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
    }

    @Test fun `enrich with identity resolution enriches identifiers`() = runTest {
        // Given — identity provider resolves MBID, art provider requires identifier
        val idProvider = FakeProvider(id = "mb", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.IdentifierResolution(musicBrainzId = "mbid-123", wikidataId = "Q123"), "mb", 0.95f)) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, requiresIdentifier = true)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching both types
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then — art provider received the resolved MBID
        assertEquals("mbid-123", artProvider.enrichCalls.first().first.identifiers.musicBrainzId)
    }

    @Test fun `getProviders returns all registered providers`() {
        // Given — two providers
        val p1 = FakeProvider(id = "a"); val p2 = FakeProvider(id = "b")

        // When / Then
        assertEquals(2, engine(p1, p2).getProviders().size)
    }

    @Test fun `enrich with empty types returns empty map`() = runTest {
        // When / Then
        assertTrue(engine().enrich(req, emptySet()).isEmpty())
    }

    @Test fun `search returns candidates from identity provider`() = runTest {
        // Given — identity provider with search capability
        val candidate = SearchCandidate(
            title = "OK Computer", artist = "Radiohead", year = "1997",
            country = "GB", releaseType = "Album", score = 98,
            thumbnailUrl = null, identifiers = EnrichmentIdentifiers(musicBrainzId = "abc"),
            provider = "mb",
        )
        val p = FakeProviderWithSearch(
            id = "mb",
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
            candidates = listOf(candidate),
        )

        // When — searching
        val results = engine(p).search(req, 10)

        // Then — returns the candidate
        assertEquals(1, results.size)
        assertEquals("OK Computer", results[0].title)
    }

    @Test fun `search returns empty list when no identity provider`() = runTest {
        // Given — engine with no providers

        // When — searching
        val results = engine().search(req, 10)

        // Then — empty results
        assertTrue(results.isEmpty())
    }

    // --- Identity resolution side-effects ---

    @Test fun `identity resolution extracts Metadata from IdentifierResolution`() = runTest {
        // Given — identity provider returns IdentifierResolution with nested Metadata
        val resolution = EnrichmentData.IdentifierResolution(
            musicBrainzId = "mbid-123",
            wikidataId = "Q123",
            metadata = EnrichmentData.Metadata(genres = listOf("rock", "alternative"), label = "Parlophone"),
        )
        val idProvider = FakeProvider(id = "mb", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, resolution, "mb", 0.95f)) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching GENRE and LABEL (both identity types)
        val results = e.enrich(req, setOf(EnrichmentType.GENRE, EnrichmentType.LABEL))

        // Then — GENRE result is Metadata (not IdentifierResolution) with resolved identifiers
        val genreResult = results[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertTrue("Expected Metadata but got ${genreResult.data::class.simpleName}", genreResult.data is EnrichmentData.Metadata)
        assertEquals(listOf("rock", "alternative"), (genreResult.data as EnrichmentData.Metadata).genres)
        assertNotNull(genreResult.resolvedIdentifiers)
        assertEquals("mbid-123", genreResult.resolvedIdentifiers?.musicBrainzId)

        // Then — LABEL also gets the shared Metadata result
        val labelResult = results[EnrichmentType.LABEL] as EnrichmentResult.Success
        assertEquals("Parlophone", (labelResult.data as EnrichmentData.Metadata).label)
    }

    @Test fun `identity resolution updates request identifiers for downstream providers`() = runTest {
        // Given — identity provider resolves wikidataId + wikipediaTitle, bio provider needs them
        val resolution = EnrichmentData.IdentifierResolution(
            musicBrainzId = "mbid-456", wikidataId = "Q456", wikipediaTitle = "Radiohead",
            metadata = EnrichmentData.Metadata(genres = listOf("rock")),
        )
        val idProvider = FakeProvider(id = "mb", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, resolution, "mb", 0.95f)) }
        val bioProvider = FakeProvider(id = "wp", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100, requiresIdentifier = true)))
            .also { it.givenResult(EnrichmentType.ARTIST_BIO, EnrichmentResult.Success(EnrichmentType.ARTIST_BIO, EnrichmentData.Biography("bio text", "Wikipedia"), "wp", 0.9f)) }
        val artistReq = EnrichmentRequest.forArtist("Radiohead")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, bioProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching genre + bio
        e.enrich(artistReq, setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO))

        // Then — bio provider received the enriched identifiers from identity resolution
        assertEquals(1, bioProvider.enrichCalls.size)
        val enrichedReq = bioProvider.enrichCalls.first().first
        assertEquals("Q456", enrichedReq.identifiers.wikidataId)
        assertEquals("Radiohead", enrichedReq.identifiers.wikipediaTitle)
    }

    @Test fun `identity resolution without metadata still resolves via provider chain`() = runTest {
        // Given — identity provider returns IdentifierResolution with null metadata
        val resolution = EnrichmentData.IdentifierResolution(musicBrainzId = "mbid-789", metadata = null)
        val idProvider = FakeProvider(id = "mb", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, resolution, "mb", 0.95f)) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching GENRE
        val results = e.enrich(req, setOf(EnrichmentType.GENRE))

        // Then — GENRE still resolves via provider chain (identity provider called again)
        assertTrue(results[EnrichmentType.GENRE] is EnrichmentResult.Success)
    }

    // --- Manual selection flag ---

    @Test fun `enrich preserves manually selected cache entries`() = runTest {
        // Given — cache has a manually selected art result
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p-new")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.put(key, EnrichmentType.ALBUM_ART, art("user-selected"))
        cache.markManuallySelected(key, EnrichmentType.ALBUM_ART)

        // When — enriching (cache-first)
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — manual selection preserved, provider not called
        val result = results[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("user-selected", result.provider)
        assertEquals(0, p.enrichCalls.size)
    }

    // --- Search fallback ---

    @Test fun `search supplements from secondary providers when primary has few results`() = runTest {
        // Given — MB returns 1 candidate, Deezer has a different album
        val mbCandidate = SearchCandidate("OK Computer", "Radiohead", "1997", "GB", "Album", 98, null, EnrichmentIdentifiers(musicBrainzId = "abc"), "mb")
        val deezerCandidate = SearchCandidate("The Bends", "Radiohead", null, null, null, 75, "https://img.deezer.com/123", EnrichmentIdentifiers(), "deezer")
        val mb = FakeProviderWithSearch(id = "mb", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)), candidates = listOf(mbCandidate))
        val deezer = FakeProviderWithSearch(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)), candidates = listOf(deezerCandidate))

        // When — searching with limit 10 (primary only has 1)
        val results = engine(mb, deezer).search(req, 10)

        // Then — primary first, supplemental appended
        assertEquals(2, results.size)
        assertEquals("mb", results[0].provider)
        assertEquals("deezer", results[1].provider)
    }

    @Test fun `search does not call supplemental providers when primary fills limit`() = runTest {
        // Given — MB returns exactly 5 candidates, Deezer also has candidates
        val candidates = (1..5).map { i ->
            SearchCandidate("Album $i", "Artist", null, null, null, 90, null, EnrichmentIdentifiers(), "mb")
        }
        val mb = FakeProviderWithSearch(id = "mb", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)), candidates = candidates)
        val deezer = FakeProviderWithSearch(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)), candidates = listOf(
            SearchCandidate("Should Not Appear", "Artist", null, null, null, 75, null, EnrichmentIdentifiers(), "deezer"),
        ))

        // When — searching with limit 5 (primary exactly fills it)
        val results = engine(mb, deezer).search(req, 5)

        // Then — only primary results, no supplemental
        assertEquals(5, results.size)
        assertTrue(results.all { it.provider == "mb" })
    }

    // --- Confidence overrides ---

    @Test fun `confidence override replaces provider hardcoded value`() = runTest {
        // Given — iTunes returns 0.65 confidence, config overrides to 0.9
        val p = FakeProvider(id = "itunes", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "itunes", 0.65f)) }
        val overrideConfig = EnrichmentConfig(enableIdentityResolution = false, confidenceOverrides = mapOf("itunes" to 0.9f))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, FakeHttpClient(), overrideConfig)

        // When — enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — confidence is the overridden value
        assertEquals(0.9f, (results[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).confidence)
    }

    @Test fun `confidence override below minConfidence filters result`() = runTest {
        // Given — provider returns 0.8 confidence, but override sets it to 0.3 (below 0.5 threshold)
        val p = FakeProvider(id = "disc", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "disc", 0.8f)) }
        val overrideConfig = EnrichmentConfig(enableIdentityResolution = false, confidenceOverrides = mapOf("disc" to 0.3f))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, FakeHttpClient(), overrideConfig)

        // When — enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — filtered out as NotFound
        assertTrue(results[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
    }
}

private class FakeProviderWithSearch(
    id: String,
    capabilities: List<ProviderCapability>,
    private val candidates: List<SearchCandidate> = emptyList(),
) : FakeProvider(id = id, capabilities = capabilities) {
    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> = candidates.take(limit)
}
