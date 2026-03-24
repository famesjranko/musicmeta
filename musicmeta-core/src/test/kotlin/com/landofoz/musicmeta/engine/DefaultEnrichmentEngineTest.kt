package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.delay
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
        assertEquals("cached", (results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
        assertEquals(0, p.enrichCalls.size)
    }

    @Test fun `enrich skips identity resolution when all types cached`() = runTest {
        // Given — identity provider that would resolve, and both types already cached
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123"))) }
        val types = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE)
        for (type in types) {
            cache.put(DefaultEnrichmentEngine.entityKeyFor(req, type), type, art("cached"))
        }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching with everything already cached
        val results = e.enrich(req, types)

        // Then — identity resolution skipped (no providers called), identity is null
        assertNull("identity should be null when all types served from cache", results.identity)
        assertEquals(0, idProvider.enrichCalls.size)
    }

    // --- forceRefresh ---

    @Test fun `enrich with forceRefresh bypasses cache and fetches fresh data`() = runTest {
        // Given — cache has stale data, provider has fresh data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("fresh")) }
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("stale"))

        // When — enriching with forceRefresh
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true)

        // Then — fresh data returned, not stale cache
        assertEquals("fresh", (results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
    }

    // --- invalidate ---

    @Test fun `invalidate removes cached result for specific type`() = runTest {
        // Given — cache has art and genre results
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"))
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE, genre("p"))

        // When — invalidating only art
        e.invalidate(req, EnrichmentType.ALBUM_ART)

        // Then — art gone, genre still cached
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE))
    }

    @Test fun `invalidate with null type removes all cached types`() = runTest {
        // Given — cache has art and genre results
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"))
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE, genre("p"))

        // When — invalidating all types for the request
        e.invalidate(req)

        // Then — both gone
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE))
    }

    @Test fun `invalidate clears name-alias key when request has MBID`() = runTest {
        // Given — result cached under both MBID key and name-alias key
        val mbidReq = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(musicBrainzId = "mbid-123"), "OK Computer", "Radiohead")
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"))
        cache.put(DefaultEnrichmentEngine.entityKeyForName(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"))

        // When — invalidating the MBID request
        e.invalidate(mbidReq, EnrichmentType.ALBUM_ART)

        // Then — both keys cleared
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyForName(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
    }

    // --- manual selection ---

    @Test fun `markManuallySelected and isManuallySelected round-trip through cache`() = runTest {
        // Given — a result cached for the request
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"))

        // When — marking as manually selected via the engine
        assertFalse(e.isManuallySelected(req, EnrichmentType.ALBUM_ART))
        e.markManuallySelected(req, EnrichmentType.ALBUM_ART)

        // Then — manual selection flag persisted
        assertTrue(e.isManuallySelected(req, EnrichmentType.ALBUM_ART))
    }

    @Test fun `enrich fans out to provider chains`() = runTest {
        // Given — two providers, each handling a different type
        val p1 = FakeProvider(id = "art", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("art")) }
        val p2 = FakeProvider(id = "genre", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))).also { it.givenResult(EnrichmentType.GENRE, genre("genre")) }

        // When — requesting both types
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then — both resolved in parallel
        assertEquals(2, results.raw.size)
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
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
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.LYRICS_SYNCED] is EnrichmentResult.NotFound)
    }

    @Test fun `enrich filters below min confidence`() = runTest {
        // Given — provider returns result with 0.2 confidence (below 0.5 threshold)
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "p", 0.2f)) }

        // When — enriching
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — treated as NotFound
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
    }

    @Test fun `enrich with identity resolution enriches identifiers`() = runTest {
        // Given — identity provider resolves MBID, art provider requires identifier
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123", wikidataId = "Q123"))) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
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
        assertTrue(engine().enrich(req, emptySet()).raw.isEmpty())
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
            isIdentityProvider = true,
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

    @Test fun `identity resolution stores Metadata for non-mergeable identity types`() = runTest {
        // Given — identity provider returns Metadata with resolvedIdentifiers
        val metadata = EnrichmentData.Metadata(genres = listOf("rock", "alternative"), label = "Parlophone")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123", wikidataId = "Q123")))
                it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f))
            }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching GENRE and LABEL (both identity types, but GENRE is mergeable)
        val results = e.enrich(req, setOf(EnrichmentType.GENRE, EnrichmentType.LABEL))

        // Then — LABEL gets identity result (non-mergeable identity type)
        val labelResult = results.raw[EnrichmentType.LABEL] as EnrichmentResult.Success
        assertEquals("Parlophone", (labelResult.data as EnrichmentData.Metadata).label)

        // Then — GENRE goes through merge path (mergeable type, not consumed by identity)
        val genreResult = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertTrue("Expected Metadata but got ${genreResult.data::class.simpleName}", genreResult.data is EnrichmentData.Metadata)
    }

    @Test fun `identity resolution updates request identifiers for downstream providers`() = runTest {
        // Given — identity provider resolves wikidataId + wikipediaTitle, bio provider needs them
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"))
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-456", wikidataId = "Q456", wikipediaTitle = "Radiohead")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds)) }
        val bioProvider = FakeProvider(id = "wp", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100, identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE)))
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

    @Test fun `GENRE goes through merge path even with identity resolution enabled`() = runTest {
        // Given — identity provider + genre provider both contribute genreTags
        val metadata = EnrichmentData.Metadata(
            genres = listOf("rock"),
            genreTags = listOf(GenreTag("rock", 0.4f, listOf("musicbrainz"))),
        )
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-789")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds))
                it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f))
            }
        val lastfm = genreProviderWithTags("lastfm", listOf(GenreTag("alternative rock", 0.3f, listOf("lastfm"))))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, lastfm)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching GENRE with identity resolution enabled
        val results = e.enrich(req, setOf(EnrichmentType.GENRE))

        // Then — GENRE result comes from genre_merger (not identity resolution)
        val genreResult = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals("genre_merger", genreResult.provider)
        val tags = (genreResult.data as EnrichmentData.Metadata).genreTags!!
        val sources = tags.flatMap { it.sources }.distinct()
        assertTrue("Should have musicbrainz source", "musicbrainz" in sources)
        assertTrue("Should have lastfm source", "lastfm" in sources)
    }

    // --- Identity match (RESOLVED / BEST_EFFORT / SUGGESTIONS) ---

    @Test fun `enrich stamps RESOLVED with score from identity resolution`() = runTest {
        // Given — identity provider resolves with confidence 0.85 (= score 85)
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.85f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123"))) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching with identity resolution
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — RESOLVED with score
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(IdentityMatch.RESOLVED, artResult.identityMatch)
        assertEquals(85, artResult.identityMatchScore)
    }

    @Test fun `enrich leaves identityMatch null when no identity resolution needed`() = runTest {
        // Given — request already has MBID, no identity resolution needed
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }
        val reqWithMbid = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "mbid-123")

        // When — enriching (identity resolution skipped)
        val results = engine(p).enrich(reqWithMbid, setOf(EnrichmentType.ALBUM_ART))

        // Then — null identity match (pre-resolved, confident)
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertNull(artResult.identityMatch)
        assertNull(artResult.identityMatchScore)
    }

    @Test fun `enrich short-circuits with SUGGESTIONS when identity fails with candidates`() = runTest {
        // Given — identity provider returns NotFound with suggestions
        val suggestions = listOf(
            SearchCandidate("Bush", null, "1992", "GB", "Group", 75, null, EnrichmentIdentifiers(musicBrainzId = "mbid-gb"), "mb", disambiguation = "British rock band"),
            SearchCandidate("Bush", null, "1994", "CA", "Group", 70, null, EnrichmentIdentifiers(musicBrainzId = "mbid-ca"), "mb", disambiguation = "Canadian band"),
        )
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = suggestions)) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching with identity resolution that fails with suggestions
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — short-circuited: NotFound with SUGGESTIONS, downstream provider NOT called
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.NotFound
        assertEquals(IdentityMatch.SUGGESTIONS, artResult.identityMatch)
        assertEquals(2, artResult.suggestions!!.size)
        assertEquals("British rock band", artResult.suggestions!![0].disambiguation)
        assertEquals(0, artProvider.enrichCalls.size) // short-circuited, never called
    }

    @Test fun `enrich stamps BEST_EFFORT when identity fails without suggestions`() = runTest {
        // Given — identity provider returns NotFound without suggestions (truly nothing found)
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb")) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching (identity fails, providers try fuzzy search)
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — Success but marked as BEST_EFFORT
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(IdentityMatch.BEST_EFFORT, artResult.identityMatch)
        assertNull(artResult.identityMatchScore)
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
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("user-selected", result.provider)
        assertEquals(0, p.enrichCalls.size)
    }

    // --- Search fallback ---

    @Test fun `search supplements from secondary providers when primary has few results`() = runTest {
        // Given — MB returns 1 candidate, Deezer has a different album
        val mbCandidate = SearchCandidate("OK Computer", "Radiohead", "1997", "GB", "Album", 98, null, EnrichmentIdentifiers(musicBrainzId = "abc"), "mb")
        val deezerCandidate = SearchCandidate("The Bends", "Radiohead", null, null, null, 75, "https://img.deezer.com/123", EnrichmentIdentifiers(), "deezer")
        val mb = FakeProviderWithSearch(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)), candidates = listOf(mbCandidate))
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
        val mb = FakeProviderWithSearch(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)), candidates = candidates)
        val deezer = FakeProviderWithSearch(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)), candidates = listOf(
            SearchCandidate("Should Not Appear", "Artist", null, null, null, 75, null, EnrichmentIdentifiers(), "deezer"),
        ))

        // When — searching with limit 5 (primary exactly fills it)
        val results = engine(mb, deezer).search(req, 5)

        // Then — only primary results, no supplemental
        assertEquals(5, results.size)
        assertTrue(results.all { it.provider == "mb" })
    }

    // --- Data-driven needsIdentityResolution ---

    @Test fun `needsIdentityResolution triggers when provider needs MUSICBRAINZ_ID and request lacks it`() = runTest {
        // Given — identity provider + art provider requiring MUSICBRAINZ_ID, request has no MBID
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"))
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-resolved")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds)) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching ALBUM_ART with no identifiers
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — identity provider was called (needsIdentityResolution returned true)
        assertTrue("Identity provider should have been called", idProvider.enrichCalls.isNotEmpty())
    }

    @Test fun `needsIdentityResolution skips when all providers use NONE and MBID present`() = runTest {
        // Given — identity provider + art provider with NONE requirement, request has MBID
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val reqWithMbid = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(musicBrainzId = "existing-mbid"), "Test", "Artist")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching ALBUM_ART only (all providers use NONE, MBID present)
        e.enrich(reqWithMbid, setOf(EnrichmentType.ALBUM_ART))

        // Then — identity provider should NOT have been called (MBID present, no provider needs other identifiers)
        assertEquals("Identity provider should not have been called", 0, idProvider.enrichCalls.size)
    }

    @Test fun `needsIdentityResolution skips when required identifiers already present`() = runTest {
        // Given — art provider requires MUSICBRAINZ_ID, request already has MBID
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val reqWithMbid = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(musicBrainzId = "existing-mbid"), "OK Computer", "Radiohead")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching ALBUM_ART with MBID already present
        e.enrich(reqWithMbid, setOf(EnrichmentType.ALBUM_ART))

        // Then — identity provider should NOT have been called (MBID already available)
        assertEquals("Identity provider should not have been called", 0, idProvider.enrichCalls.size)
    }

    // --- Cache key convergence after disambiguation ---

    @Test fun `name-only request caches under name key after identity resolution`() = runTest {
        // Given — identity provider resolves MBID for a name-only request
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"), label = "Parlophone")
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-resolved")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds))
                it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f))
            }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, FakeHttpClient(), EnrichmentConfig(enableIdentityResolution = true))

        // When — enriching with name-only request (no MBID)
        val nameReq = EnrichmentRequest.forArtist("Radiohead")
        e.enrich(nameReq, setOf(EnrichmentType.LABEL))

        // Then — result cached under the name-based key (for future name-only lookups)
        val nameKey = DefaultEnrichmentEngine.entityKeyForName(nameReq, EnrichmentType.LABEL)
        assertNotNull("Should be cached under name key", cache.get(nameKey, EnrichmentType.LABEL))
    }

    // --- TTL on EnrichmentType ---

    @Test fun `EnrichmentType ALBUM_ART has 90-day default TTL`() {
        assertEquals(7_776_000_000L, EnrichmentType.ALBUM_ART.defaultTtlMs)
    }

    @Test fun `EnrichmentType TRACK_POPULARITY has 7-day default TTL`() {
        assertEquals(604_800_000L, EnrichmentType.TRACK_POPULARITY.defaultTtlMs)
    }

    @Test fun `EnrichmentType LABEL has 365-day default TTL`() {
        assertEquals(31_536_000_000L, EnrichmentType.LABEL.defaultTtlMs)
    }

    @Test fun `EnrichmentType ARTIST_PHOTO has 30-day default TTL`() {
        assertEquals(2_592_000_000L, EnrichmentType.ARTIST_PHOTO.defaultTtlMs)
    }

    @Test fun `engine uses ttlOverrides when configured`() = runTest {
        // Given — provider returns successful art result, config has TTL override
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }
        val overrideConfig = EnrichmentConfig(
            enableIdentityResolution = false,
            ttlOverrides = mapOf(EnrichmentType.ALBUM_ART to 999_000L),
        )
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, FakeHttpClient(), overrideConfig)

        // When — enriching
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — cache received the overridden TTL
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        assertEquals(999_000L, cache.storedTtls["$key:${EnrichmentType.ALBUM_ART}"])
    }

    @Test fun `engine falls back to type defaultTtlMs without override`() = runTest {
        // Given — provider returns successful art result, no TTL override
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }

        // When — enriching
        engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — cache received the type's default TTL (90 days)
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        assertEquals(EnrichmentType.ALBUM_ART.defaultTtlMs, cache.storedTtls["$key:${EnrichmentType.ALBUM_ART}"])
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
        assertEquals(0.9f, (results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).confidence)
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
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
    }

    // --- Composite timeline ---

    private val artistReq = EnrichmentRequest.forArtist("Radiohead")

    private fun identityProviderWithMetadata(beginDate: String? = "1985", endDate: String? = null, artistType: String? = "Group") =
        FakeProvider(
            id = "mb",
            isIdentityProvider = true,
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
        ).also {
            it.givenIdentityResult(
                EnrichmentResult.Success(
                    type = EnrichmentType.GENRE,
                    data = EnrichmentData.Metadata(artistType = artistType, beginDate = beginDate, endDate = endDate),
                    provider = "mb",
                    confidence = 0.95f,
                    resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-rh"),
                )
            )
        }

    private fun discographyProvider() =
        FakeProvider(id = "mb-disco", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.ARTIST_DISCOGRAPHY,
                    EnrichmentResult.Success(
                        type = EnrichmentType.ARTIST_DISCOGRAPHY,
                        data = EnrichmentData.Discography(albums = listOf(DiscographyAlbum("OK Computer", year = "1997"))),
                        provider = "mb-disco",
                        confidence = 0.95f,
                    )
                )
            }

    private fun bandMembersProvider() =
        FakeProvider(id = "mb-members", capabilities = listOf(ProviderCapability(EnrichmentType.BAND_MEMBERS, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.BAND_MEMBERS,
                    EnrichmentResult.Success(
                        type = EnrichmentType.BAND_MEMBERS,
                        data = EnrichmentData.BandMembers(members = listOf(BandMember("Thom Yorke", activePeriod = "1985-present"))),
                        provider = "mb-members",
                        confidence = 0.95f,
                    )
                )
            }

    @Test fun `enrich resolves ARTIST_TIMELINE from sub-types automatically`() = runTest {
        // Given — identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            FakeHttpClient(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — requesting only ARTIST_TIMELINE
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then — result is Success with ArtistTimeline containing events
        val timeline = results.raw[EnrichmentType.ARTIST_TIMELINE]
        assertTrue("Expected Success but got $timeline", timeline is EnrichmentResult.Success)
        val data = (timeline as EnrichmentResult.Success).data as EnrichmentData.ArtistTimeline
        val eventTypes = data.events.map { it.type }
        assertTrue("Expected 'formed' event", "formed" in eventTypes)
        assertTrue("Expected 'first_album' event", "first_album" in eventTypes)
        assertTrue("Expected 'member_joined' event", "member_joined" in eventTypes)
    }

    @Test fun `enrich resolves ARTIST_TIMELINE without caller specifying sub-types`() = runTest {
        // Given — identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            FakeHttpClient(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — requesting only ARTIST_TIMELINE (NOT ARTIST_DISCOGRAPHY or BAND_MEMBERS)
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then — only ARTIST_TIMELINE is in the result map; sub-types are not exposed
        assertTrue("ARTIST_TIMELINE should be in results", EnrichmentType.ARTIST_TIMELINE in results.raw)
        assertFalse("ARTIST_DISCOGRAPHY should NOT be in results", EnrichmentType.ARTIST_DISCOGRAPHY in results.raw)
        assertFalse("BAND_MEMBERS should NOT be in results", EnrichmentType.BAND_MEMBERS in results.raw)
    }

    @Test fun `ARTIST_TIMELINE gracefully degrades when sub-types return NotFound`() = runTest {
        // Given — identity metadata with beginDate, but no discography or band members providers
        val idProvider = identityProviderWithMetadata(beginDate = "1985", artistType = "Group")
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider)),
            cache,
            FakeHttpClient(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — requesting ARTIST_TIMELINE with no sub-type providers
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then — still a Success with a partial timeline containing just the life-span event
        val timeline = results.raw[EnrichmentType.ARTIST_TIMELINE]
        assertTrue("Expected Success but got $timeline", timeline is EnrichmentResult.Success)
        val data = (timeline as EnrichmentResult.Success).data as EnrichmentData.ArtistTimeline
        assertEquals(1, data.events.size)
        assertEquals("formed", data.events[0].type)
        assertEquals("1985", data.events[0].date)
    }

    @Test fun `ARTIST_TIMELINE includes sub-type results when caller also requests them`() = runTest {
        // Given — identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            FakeHttpClient(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — requesting ARTIST_TIMELINE + ARTIST_DISCOGRAPHY explicitly
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE, EnrichmentType.ARTIST_DISCOGRAPHY))

        // Then — both are in the result map
        assertTrue("ARTIST_TIMELINE should be in results", results.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.Success)
        assertTrue("ARTIST_DISCOGRAPHY should be in results", results.raw[EnrichmentType.ARTIST_DISCOGRAPHY] is EnrichmentResult.Success)
    }

    // --- Genre merging via mergeable type path (GENR-02, GENR-03, GENR-04) ---

    private fun genreProviderWithTags(id: String, tags: List<GenreTag>) =
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genreTags = tags), id, 0.9f)) }

    @Test fun `GENRE type merges results from multiple providers`() = runTest {
        // Given — two providers each returning Metadata with different genreTags
        val p1 = genreProviderWithTags("p1", listOf(GenreTag("rock", 0.8f, listOf("p1"))))
        val p2 = genreProviderWithTags("p2", listOf(GenreTag("alternative", 0.7f, listOf("p2")), GenreTag("rock", 0.6f, listOf("p2"))))

        // When — enriching with GENRE
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.GENRE))

        // Then — result is Success with merged genreTags (rock combined from both providers)
        val result = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        val metadata = result.data as EnrichmentData.Metadata
        assertNotNull("genreTags should not be null", metadata.genreTags)
        val tagNames = metadata.genreTags!!.map { it.name }
        assertTrue("rock should be in merged tags", "rock" in tagNames)
        assertTrue("alternative should be in merged tags", "alternative" in tagNames)
        // rock was contributed by 2 providers, should have higher confidence
        val rockTag = metadata.genreTags!!.first { it.name == "rock" }
        assertTrue("rock confidence should be additive from both providers", rockTag.confidence > 0.8f)
    }

    @Test fun `GENRE merged result populates backward-compatible genres list`() = runTest {
        // Given — two providers each returning genreTags
        val p1 = genreProviderWithTags("p1", listOf(GenreTag("rock", 0.9f, listOf("p1")), GenreTag("alternative", 0.7f, listOf("p1"))))
        val p2 = genreProviderWithTags("p2", listOf(GenreTag("indie", 0.6f, listOf("p2"))))

        // When — enriching with GENRE
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.GENRE))

        // Then — genres list is populated from top merged tag names
        val result = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        val metadata = result.data as EnrichmentData.Metadata
        assertNotNull("genres list should be populated for backward compatibility", metadata.genres)
        assertTrue("genres should contain rock", "rock" in metadata.genres!!)
        assertTrue("genres should contain alternative", "alternative" in metadata.genres!!)
    }

    @Test fun `GENRE merge uses genre_merger as provider`() = runTest {
        // Given — one provider with genreTags
        val p1 = genreProviderWithTags("p1", listOf(GenreTag("jazz", 0.9f, listOf("p1"))))

        // When — enriching with GENRE
        val results = engine(p1).enrich(req, setOf(EnrichmentType.GENRE))

        // Then — provider field is genre_merger
        val result = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals("genre_merger", result.provider)
    }

    @Test fun `non-GENRE types still short-circuit on first success`() = runTest {
        // Given — two providers both capable of ALBUM_ART; first one succeeds
        val artResult = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x.com/art.jpg"), "p1", 0.95f)
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, artResult) }
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When — enriching with ALBUM_ART
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — p1 wins, p2 never called (short-circuit preserved)
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("p1", result.provider)
        assertEquals(0, p2.enrichCalls.size)
    }

    @Test fun `ARTIST_TIMELINE returns NotFound for ForAlbum requests`() = runTest {
        // Given — identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            FakeHttpClient(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — requesting ARTIST_TIMELINE for a ForAlbum request
        val results = e.enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then — NotFound because timelines are ForArtist-only
        assertTrue(
            "Expected NotFound but got ${results.raw[EnrichmentType.ARTIST_TIMELINE]}",
            results.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.NotFound,
        )
    }

    // --- SIMILAR_ARTISTS multi-provider merge (SIM-02, SIM-03) ---

    private fun similarArtistsProvider(id: String, artists: List<SimilarArtist>) =
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.SIMILAR_ARTISTS,
                    EnrichmentResult.Success(
                        type = EnrichmentType.SIMILAR_ARTISTS,
                        data = EnrichmentData.SimilarArtists(artists = artists),
                        provider = id,
                        confidence = 0.9f,
                    )
                )
            }

    @Test fun `enrich merges SIMILAR_ARTISTS from multiple providers`() = runTest {
        // Given — provider A returns Muse + Bjork, provider B returns Muse + Portishead
        val providerA = similarArtistsProvider(
            id = "lastfm",
            artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
                SimilarArtist("Bjork", matchScore = 0.7f, sources = listOf("lastfm")),
            )
        )
        val providerB = similarArtistsProvider(
            id = "deezer",
            artists = listOf(
                SimilarArtist("Muse", matchScore = 0.5f, sources = listOf("deezer")),
                SimilarArtist("Portishead", matchScore = 0.8f, sources = listOf("deezer")),
            )
        )
        val artistRequest = EnrichmentRequest.forArtist("Radiohead")
        // Engine built with both GenreMerger and SimilarArtistMerger (mirrors Builder defaults)
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(providerA, providerB)),
            cache,
            FakeHttpClient(),
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When — enriching SIMILAR_ARTISTS with both providers
        val results = e.enrich(artistRequest, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then — result is Success from the merger
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals("similar_artist_merger", result.provider)
        val data = result.data as EnrichmentData.SimilarArtists

        // Then — Muse appears once (deduplicated), with both sources, matchScore capped at 1.0
        val muse = data.artists.first { it.name == "Muse" }
        assertTrue("lastfm" in muse.sources)
        assertTrue("deezer" in muse.sources)
        assertEquals(1.0f, muse.matchScore, 0.001f)

        // Then — Bjork and Portishead each appear once with their original single-provider sources
        val bjork = data.artists.first { it.name == "Bjork" }
        assertEquals(listOf("lastfm"), bjork.sources)
        val portishead = data.artists.first { it.name == "Portishead" }
        assertEquals(listOf("deezer"), portishead.sources)

        // Then — results sorted by matchScore descending (Muse=1.0, Portishead=0.8, Bjork=0.7)
        val scores = data.artists.map { it.matchScore }
        assertEquals(scores, scores.sortedDescending())
    }

    @Test fun `SIMILAR_ARTISTS merge still works when one provider errors`() = runTest {
        // Given — provider A returns artists, provider B throws an exception
        val providerA = similarArtistsProvider(
            id = "lastfm",
            artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
                SimilarArtist("Bjork", matchScore = 0.7f, sources = listOf("lastfm")),
            )
        )
        val providerB = FakeProvider(
            id = "deezer",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 30)),
        ).also {
            it.givenResult(
                EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentResult.Error(EnrichmentType.SIMILAR_ARTISTS, "deezer", "API timeout"),
            )
        }
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(providerA, providerB)),
            cache,
            FakeHttpClient(),
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When — enriching with one erroring provider
        val results = e.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )

        // Then — still returns merged result from the working provider
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals("similar_artist_merger", result.provider)
        val data = result.data as EnrichmentData.SimilarArtists
        assertEquals(2, data.artists.size)
        assertTrue(data.artists.all { "lastfm" in it.sources })
    }

    @Test fun `SIMILAR_ARTISTS returns NotFound when all providers error`() = runTest {
        // Given — both providers return errors
        val providerA = FakeProvider(
            id = "lastfm",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentResult.Error(EnrichmentType.SIMILAR_ARTISTS, "lastfm", "Rate limited"),
            )
        }
        val providerB = FakeProvider(
            id = "deezer",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 30)),
        ).also {
            it.givenResult(
                EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentResult.Error(EnrichmentType.SIMILAR_ARTISTS, "deezer", "API timeout"),
            )
        }
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(providerA, providerB)),
            cache,
            FakeHttpClient(),
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When
        val results = e.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )

        // Then — NotFound since no provider succeeded
        assertTrue(
            "Expected NotFound but got ${results.raw[EnrichmentType.SIMILAR_ARTISTS]}",
            results.raw[EnrichmentType.SIMILAR_ARTISTS] is EnrichmentResult.NotFound,
        )
    }

    @Test fun `SIMILAR_ARTISTS merge skips unavailable provider`() = runTest {
        // Given — provider A is available with results, provider B is unavailable
        val providerA = similarArtistsProvider(
            id = "deezer",
            artists = listOf(
                SimilarArtist("Portishead", matchScore = 0.8f, sources = listOf("deezer")),
            )
        )
        val providerB = FakeProvider(
            id = "lastfm",
            capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
            isAvailable = false,
        )
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(providerA, providerB)),
            cache,
            FakeHttpClient(),
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When
        val results = e.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )

        // Then — only available provider's results returned
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val data = result.data as EnrichmentData.SimilarArtists
        assertEquals(1, data.artists.size)
        assertEquals("Portishead", data.artists[0].name)
        assertEquals(listOf("deezer"), data.artists[0].sources)
        // Unavailable provider should not have been called
        assertEquals(0, providerB.enrichCalls.size)
    }

    @Test fun `timeout backfills missing types with Error TIMEOUT`() = runTest {
        // Given — a slow provider that exceeds the timeout
        val slow = SlowProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            delayMs = 5_000,
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, art("slow")) }
        val shortTimeout = EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100)
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(slow)), cache, FakeHttpClient(), shortTimeout)

        // When — enriching with a timeout shorter than the provider's delay
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — timed-out type gets Error with TIMEOUT kind
        val result = results.raw[EnrichmentType.ALBUM_ART]
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (result as EnrichmentResult.Error).errorKind)
        assertEquals("engine", result.provider)
    }

    @Test fun `timeout preserves cached results alongside TIMEOUT errors`() = runTest {
        // Given — one type is cached, another needs a slow provider
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE, genre("cached"))
        val slow = SlowProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            delayMs = 5_000,
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, art("slow")) }
        val shortTimeout = EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100)
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(slow)), cache, FakeHttpClient(), shortTimeout)

        // When — requesting both types
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then — cached type returned normally, slow type gets TIMEOUT error
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        val artResult = results.raw[EnrichmentType.ALBUM_ART]
        assertTrue("Expected Error but got $artResult", artResult is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (artResult as EnrichmentResult.Error).errorKind)
    }

    @Test fun `timeout does not cache Error TIMEOUT results`() = runTest {
        // Given — slow provider that will time out
        val slow = SlowProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            delayMs = 5_000,
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, art("slow")) }
        val shortTimeout = EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100)
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(slow)), cache, FakeHttpClient(), shortTimeout)

        // When — enriching (will time out)
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — nothing cached (Error results are never cached)
        assertTrue(cache.stored.isEmpty())
    }

    // --- stale cache ---

    private val staleConfig = EnrichmentConfig(
        enableIdentityResolution = false,
        cacheMode = com.landofoz.musicmeta.cache.CacheMode.STALE_IF_ERROR,
    )
    private fun staleEngine(vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, FakeHttpClient(), staleConfig)

    @Test fun `STALE_IF_ERROR serves expired cache when provider returns Error`() = runTest {
        // Given — provider returns Error, expiredStore has stale art data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When — enriching with STALE_IF_ERROR config
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — returns expired cache entry with isStale=true
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("stale-provider", result.provider)
        assertTrue("Result should be marked stale", result.isStale)
    }

    @Test fun `STALE_IF_ERROR serves expired cache when provider returns RateLimited`() = runTest {
        // Given — provider returns RateLimited, expiredStore has stale art data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.RateLimited(EnrichmentType.ALBUM_ART, "p")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When — enriching with STALE_IF_ERROR config
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — returns expired cache entry with isStale=true
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("stale-provider", result.provider)
        assertTrue("Result should be marked stale", result.isStale)
    }

    @Test fun `STALE_IF_ERROR does not serve stale for genuine NotFound`() = runTest {
        // Given — provider returns NotFound, expiredStore has stale art data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        // FakeProvider returns NotFound by default when no result is configured
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When — enriching with STALE_IF_ERROR config
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — NotFound is preserved, stale cache NOT served (provider searched and found nothing)
        assertTrue(
            "Expected NotFound but got ${results.raw[EnrichmentType.ALBUM_ART]}",
            results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound,
        )
    }

    @Test fun `NETWORK_FIRST does not serve stale when provider fails`() = runTest {
        // Given — default NETWORK_FIRST config, provider returns Error, expiredStore has stale data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When — enriching with default NETWORK_FIRST config (uses engine(), not staleEngine())
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — Error returned, stale fallback NOT applied
        assertTrue(
            "Expected Error but got ${results.raw[EnrichmentType.ALBUM_ART]}",
            results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Error,
        )
    }

    @Test fun `stale result is not re-written to cache`() = runTest {
        // Given — provider returns Error, expiredStore has stale art data, stored is empty
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When — enriching with STALE_IF_ERROR (stale is served)
        staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then — stale result was NOT re-cached with a fresh TTL (stored remains empty)
        assertTrue("Stale result should not be written to fresh cache", cache.stored.isEmpty())
    }

    @Test fun `ARTIST_TIMELINE is cached like standard types`() = runTest {
        // Given — identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            FakeHttpClient(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — first enrich call
        e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))
        val discoCallsAfterFirst = discoProvider.enrichCalls.size

        // When — second enrich call for the same request
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then — ARTIST_TIMELINE is returned from cache; discography provider not called again
        assertTrue("ARTIST_TIMELINE should be Success on second call", results.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.Success)
        assertEquals("Discography provider should not be called again on cache hit", discoCallsAfterFirst, discoProvider.enrichCalls.size)
    }
}

private class SlowProvider(
    id: String,
    capabilities: List<ProviderCapability>,
    private val delayMs: Long,
) : FakeProvider(id = id, capabilities = capabilities) {
    override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
        delay(delayMs)
        return super.enrich(request, type)
    }
}

private class FakeProviderWithSearch(
    id: String,
    capabilities: List<ProviderCapability>,
    isIdentityProvider: Boolean = false,
    private val candidates: List<SearchCandidate> = emptyList(),
) : FakeProvider(id = id, capabilities = capabilities, isIdentityProvider = isIdentityProvider) {
    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> = candidates.take(limit)
}
