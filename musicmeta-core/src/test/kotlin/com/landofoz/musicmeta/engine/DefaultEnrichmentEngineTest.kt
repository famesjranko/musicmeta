package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.http.AuthException
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
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
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, config)

    @Test fun `enrich returns cached result`() = runTest {
        // Given - cache pre-populated with an art result
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("cached"), CanonicalStatus.RESOLVED)

        // When - enriching with a provider that would return different data
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - cached result returned, provider never called
        assertEquals("cached", (results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
        assertEquals(0, p.enrichCalls.size)
    }

    @Test fun `enrich skips identity resolution when all types cached`() = runTest {
        // Given - identity provider that would resolve, and both types already cached
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123"))) }
        val types = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE)
        for (type in types) {
            cache.put(DefaultEnrichmentEngine.entityKeyFor(req, type), type, art("cached"), CanonicalStatus.RESOLVED)
        }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching with everything already cached
        val results = e.enrich(req, types)

        // Then - identity resolution skipped (no providers called), reported honestly as a cache hit
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, results.identity.status)
        assertEquals(0, idProvider.enrichCalls.size)
    }

    // --- forceRefresh ---

    @Test fun `enrich with forceRefresh bypasses cache and fetches fresh data`() = runTest {
        // Given - cache has stale data, provider has fresh data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("fresh")) }
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("stale"), CanonicalStatus.RESOLVED)

        // When - enriching with forceRefresh
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true)

        // Then - fresh data returned, not stale cache
        assertEquals("fresh", (results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).provider)
    }

    // --- invalidate ---

    @Test fun `invalidate removes cached result for specific type`() = runTest {
        // Given - cache has art and genre results
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"), CanonicalStatus.RESOLVED)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE, genre("p"), CanonicalStatus.RESOLVED)

        // When - invalidating only art
        e.invalidate(req, EnrichmentType.ALBUM_ART)

        // Then - art gone, genre still cached
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE))
    }

    @Test fun `invalidate with null type removes all cached types`() = runTest {
        // Given - cache has art and genre results
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"), CanonicalStatus.RESOLVED)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE, genre("p"), CanonicalStatus.RESOLVED)

        // When - invalidating all types for the request
        e.invalidate(req)

        // Then - both gone
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE))
    }

    @Test fun `invalidate clears name-alias key when request has MBID`() = runTest {
        // Given - result cached under both MBID key and name-alias key
        val mbidReq = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(musicBrainzId = "mbid-123"), "OK Computer", "Radiohead")
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"), CanonicalStatus.RESOLVED)
        cache.put(DefaultEnrichmentEngine.entityKeyForName(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"), CanonicalStatus.RESOLVED)

        // When - invalidating the MBID request
        e.invalidate(mbidReq, EnrichmentType.ALBUM_ART)

        // Then - both keys cleared
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyFor(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
        assertNull(cache.get(DefaultEnrichmentEngine.entityKeyForName(mbidReq, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
    }

    // --- manual selection ---

    @Test fun `markManuallySelected and isManuallySelected round-trip through cache`() = runTest {
        // Given - a result cached for the request
        val p = FakeProvider(id = "p")
        val e = engine(p)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART, art("p"), CanonicalStatus.RESOLVED)

        // When - marking as manually selected via the engine
        assertFalse(e.isManuallySelected(req, EnrichmentType.ALBUM_ART))
        e.markManuallySelected(req, EnrichmentType.ALBUM_ART)

        // Then - manual selection flag persisted
        assertTrue(e.isManuallySelected(req, EnrichmentType.ALBUM_ART))
    }

    @Test fun `enrich fans out to provider chains`() = runTest {
        // Given - two providers, each handling a different type
        val p1 = FakeProvider(id = "art", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("art")) }
        val p2 = FakeProvider(id = "genre", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))).also { it.givenResult(EnrichmentType.GENRE, genre("genre")) }

        // When - requesting both types
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then - both resolved in parallel
        assertEquals(2, results.raw.size)
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
    }

    @Test fun `enrich caches successful results`() = runTest {
        // Given - provider returns successful art result
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }

        // When - enriching
        engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - result persisted to cache
        assertFalse(cache.stored.isEmpty())
    }

    @Test fun `enrich does not cache errors`() = runTest {
        // Given - provider returns an error
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "err")) }

        // When - enriching
        engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - nothing cached
        assertTrue(cache.stored.isEmpty())
    }

    @Test fun `enrich returns partial results`() = runTest {
        // Given - art provider exists but no lyrics provider
        val p = FakeProvider(id = "art", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100))).also { it.givenResult(EnrichmentType.ALBUM_ART, art("art")) }

        // When - requesting art + lyrics
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.LYRICS_SYNCED))

        // Then - art succeeds, lyrics returns NotFound
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.LYRICS_SYNCED] is EnrichmentResult.NotFound)
    }

    @Test fun `enrich filters below min confidence`() = runTest {
        // Given - provider returns result with 0.2 confidence (below 0.5 threshold)
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "p", 0.2f)) }

        // When - enriching
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - treated as NotFound
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
    }

    @Test fun `enrich with identity resolution enriches identifiers`() = runTest {
        // Given - identity provider resolves MBID, art provider requires identifier
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123", wikidataId = "Q123"))) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching both types
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then - art provider received the resolved MBID
        assertEquals("mbid-123", artProvider.enrichCalls.first().first.identifiers.musicBrainzId)
    }

    @Test fun `getProviders returns all registered providers`() {
        // Given - two providers
        val p1 = FakeProvider(id = "a"); val p2 = FakeProvider(id = "b")

        // When - getProviders is called
        // Then - both are returned
        assertEquals(2, engine(p1, p2).getProviders().size)
    }

    @Test fun `enrich with empty types returns empty map`() = runTest {
        // Given - an empty set of types
        // When - enrich is called
        // Then - the result map is empty
        assertTrue(engine().enrich(req, emptySet()).raw.isEmpty())
    }

    @Test fun `search returns candidates from identity provider`() = runTest {
        // Given - identity provider with search capability
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

        // When - searching
        val results = engine(p).search(req, 10)

        // Then - returns the candidate
        assertEquals(1, results.size)
        assertEquals("OK Computer", results[0].title)
    }

    @Test fun `search returns empty list when no identity provider`() = runTest {
        // Given - engine with no providers

        // When - searching
        val results = engine().search(req, 10)

        // Then - empty results
        assertTrue(results.isEmpty())
    }

    // --- Identity resolution side-effects ---

    @Test fun `identity resolution stores Metadata for non-mergeable identity types`() = runTest {
        // Given - identity provider returns Metadata with resolvedIdentifiers
        val metadata = EnrichmentData.Metadata(genres = listOf("rock", "alternative"), label = "Parlophone")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123", wikidataId = "Q123")))
                it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f))
            }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching GENRE and LABEL (both identity types, but GENRE is mergeable)
        val results = e.enrich(req, setOf(EnrichmentType.GENRE, EnrichmentType.LABEL))

        // Then - LABEL gets identity result (non-mergeable identity type)
        val labelResult = results.raw[EnrichmentType.LABEL] as EnrichmentResult.Success
        assertEquals("Parlophone", (labelResult.data as EnrichmentData.Metadata).label)

        // Then - GENRE goes through merge path (mergeable type, not consumed by identity)
        val genreResult = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertTrue("Expected Metadata but got ${genreResult.data::class.simpleName}", genreResult.data is EnrichmentData.Metadata)
    }

    @Test fun `identity resolution updates request identifiers for downstream providers`() = runTest {
        // Given - identity provider resolves wikidataId + wikipediaTitle, bio provider needs them
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"))
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-456", wikidataId = "Q456", wikipediaTitle = "Radiohead")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds)) }
        val bioProvider = FakeProvider(id = "wp", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100, identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE)))
            .also { it.givenResult(EnrichmentType.ARTIST_BIO, EnrichmentResult.Success(EnrichmentType.ARTIST_BIO, EnrichmentData.Biography("bio text", "Wikipedia"), "wp", 0.9f)) }
        val artistReq = EnrichmentRequest.forArtist("Radiohead")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, bioProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching genre + bio
        e.enrich(artistReq, setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO))

        // Then - bio provider received the enriched identifiers from identity resolution
        assertEquals(1, bioProvider.enrichCalls.size)
        val enrichedReq = bioProvider.enrichCalls.first().first
        assertEquals("Q456", enrichedReq.identifiers.wikidataId)
        assertEquals("Radiohead", enrichedReq.identifiers.wikipediaTitle)
    }

    @Test fun `GENRE goes through merge path even with identity resolution enabled`() = runTest {
        // Given - identity provider + genre provider both contribute genreTags
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
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, lastfm)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching GENRE with identity resolution enabled
        val results = e.enrich(req, setOf(EnrichmentType.GENRE))

        // Then - GENRE result comes from genre_merger (not identity resolution)
        val genreResult = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals("genre_merger", genreResult.provider)
        val tags = (genreResult.data as EnrichmentData.Metadata).genreTags!!
        val sources = tags.flatMap { it.sources }.distinct()
        assertTrue("Should have musicbrainz source", "musicbrainz" in sources)
        assertTrue("Should have lastfm source", "lastfm" in sources)
    }

    // --- Identity match (RESOLVED / BEST_EFFORT / SUGGESTIONS) ---

    @Test fun `enrich stamps RESOLVED with score from identity resolution`() = runTest {
        // Given - identity provider resolves with confidence 0.85 (= score 85)
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.85f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123"))) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching with identity resolution
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - RESOLVED with score, and the downstream result keyed on the resolved canonical id
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
        assertEquals(85, results.identity.matchScore)
        assertEquals(LookupProvenance.CANONICAL_ID, artResult.provenance)
    }

    @Test fun `enrich reports NOT_ATTEMPTED_NOT_REQUIRED when no identity resolution needed`() = runTest {
        // Given - identity resolution enabled, but the request already has an MBID and needs none
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }
        val reqWithMbid = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "mbid-123")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching (identity resolution skipped)
        val results = e.enrich(reqWithMbid, setOf(EnrichmentType.ALBUM_ART))

        // Then - not attempted because it was not required, never a bare absence
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED, results.identity.status)
        assertNull(results.identity.matchScore)
    }

    @Test fun `enrich reports NOT_ATTEMPTED_DISABLED when identity resolution is turned off`() = runTest {
        // Given - a request that would need identity resolution, on an engine with it disabled
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }

        // When - enriching with the default (disabled) config
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - disabled is distinguishable from not-required
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, results.identity.status)
    }

    @Test fun `enrich fans out to an eligible provider when identity fails with candidates`() = runTest {
        // Given - identity provider returns NotFound with suggestions, and a downstream provider
        // whose capability names no identifier requirement
        val suggestions = listOf(
            SearchCandidate("Bush", null, "1992", "GB", "Group", 75, null, EnrichmentIdentifiers(musicBrainzId = "mbid-gb"), "mb", disambiguation = "British rock band"),
            SearchCandidate("Bush", null, "1994", "CA", "Group", 70, null, EnrichmentIdentifiers(musicBrainzId = "mbid-ca"), "mb", disambiguation = "Canadian band"),
        )
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = suggestions)) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching with identity resolution that fails with suggestions
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the eligible provider still ran and its Success is best-effort; the suggestions
        // stay once at the top level instead of being copied onto the per-type result
        assertEquals(1, artProvider.enrichCalls.size)
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, artResult.provenance)
        assertEquals(CanonicalStatus.AMBIGUOUS, results.identity.status)
        assertEquals(2, results.identity.suggestions.size)
        assertEquals("British rock band", results.identity.suggestions[0].disambiguation)
    }

    @Test fun `enrich reports UNRESOLVED when identity fails without suggestions`() = runTest {
        // Given - identity provider returns NotFound without suggestions (truly nothing found)
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb")) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching (identity fails, providers try fuzzy search)
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - Success from an unresolved canonical call, provenance reflecting the fuzzy search
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(CanonicalStatus.UNRESOLVED, results.identity.status)
        assertEquals(LookupProvenance.FUZZY_NAME, artResult.provenance)
        assertNull(results.identity.matchScore)
    }

    @Test fun `enrich reports FAILED when identity provider throws`() = runTest {
        // Given - identity provider throws (transient network failure)
        val idProvider = object : FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))) {
            override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult =
                throw RuntimeException("connection reset")
        }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching (identity throws, providers still try fuzzy search)
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - identity is FAILED, never a not-attempted status, and the fuzzy result is provenance-tagged
        assertEquals(CanonicalStatus.FAILED, results.identity.status)
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, artResult.provenance)
        assertNull(results.identity.matchScore)
    }

    @Test fun `enrich reports FAILED when identity provider returns Error`() = runTest {
        // Given - identity provider returns Error (e.g. mapped transport failure)
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Error(EnrichmentType.GENRE, "mb", "timeout", errorKind = ErrorKind.NETWORK)) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching for ALBUM_ART
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - same FAILED treatment as the throwing path
        assertEquals(CanonicalStatus.FAILED, results.identity.status)
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, artResult.provenance)
    }

    @Test fun `FAILED results are not cached so a retry re-resolves`() = runTest {
        // Given - identity provider that throws once, then resolves
        var identityCalls = 0
        val idProvider = object : FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))) {
            override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult {
                identityCalls++
                if (identityCalls == 1) throw RuntimeException("connection reset")
                return EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.9f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123"))
            }
        }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - first run fails identity, second run (the user's retry) succeeds
        val first = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the FAILED result was not cached: the retry re-ran identity resolution and came
        // back RESOLVED, instead of a cache hit reporting NOT_ATTEMPTED_CACHE_HIT with stale data
        assertEquals(CanonicalStatus.FAILED, first.identity.status)
        assertEquals(2, identityCalls)
        assertEquals(CanonicalStatus.RESOLVED, second.identity.status)
        val artResult = second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, artResult.provenance)
    }

    @Test fun `identity provider failure is classified by mapError not collapsed to UNKNOWN`() = runTest {
        // Given - identity provider throws an auth failure, and a synthesizer that captures the
        // identity result (the only seam through which the engine hands it to a consumer)
        val idProvider = object : FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))) {
            override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult =
                throw AuthException(401)
        }
        var captured: EnrichmentResult? = null
        val capturing = object : CompositeSynthesizer {
            override val type = EnrichmentType.ARTIST_TIMELINE
            override val dependencies = emptySet<EnrichmentType>()
            override fun synthesize(
                resolved: Map<EnrichmentType, EnrichmentResult>,
                identityResult: EnrichmentResult?,
                request: EnrichmentRequest,
            ): EnrichmentResult {
                captured = identityResult
                return EnrichmentResult.NotFound(type, "test")
            }
        }
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider)), cache, EnrichmentConfig(enableIdentityResolution = true),
            synthesizers = listOf(capturing),
        )

        // When - enriching for ARTIST_TIMELINE
        e.enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - AUTH, not UNKNOWN: consumers key retry policy off ErrorKind, and an auth failure
        // must not be retried like a transient one
        val error = captured as EnrichmentResult.Error
        assertEquals(ErrorKind.AUTH, error.errorKind)
        assertEquals("mb", error.provider)
    }

    // --- Manual selection flag ---

    @Test fun `enrich preserves manually selected cache entries`() = runTest {
        // Given - cache has a manually selected art result
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p-new")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.put(key, EnrichmentType.ALBUM_ART, art("user-selected"), CanonicalStatus.RESOLVED)
        cache.markManuallySelected(key, EnrichmentType.ALBUM_ART)

        // When - enriching (cache-first)
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - manual selection preserved, provider not called
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("user-selected", result.provider)
        assertEquals(0, p.enrichCalls.size)
    }

    // --- Search fallback ---

    @Test fun `search supplements from secondary providers when primary has few results`() = runTest {
        // Given - MB returns 1 candidate, Deezer has a different album
        val mbCandidate = SearchCandidate("OK Computer", "Radiohead", "1997", "GB", "Album", 98, null, EnrichmentIdentifiers(musicBrainzId = "abc"), "mb")
        val deezerCandidate = SearchCandidate("The Bends", "Radiohead", null, null, null, 75, "https://img.deezer.com/123", EnrichmentIdentifiers(), "deezer")
        val mb = FakeProviderWithSearch(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)), candidates = listOf(mbCandidate))
        val deezer = FakeProviderWithSearch(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)), candidates = listOf(deezerCandidate))

        // When - searching with limit 10 (primary only has 1)
        val results = engine(mb, deezer).search(req, 10)

        // Then - primary first, supplemental appended
        assertEquals(2, results.size)
        assertEquals("mb", results[0].provider)
        assertEquals("deezer", results[1].provider)
    }

    @Test fun `search does not call supplemental providers when primary fills limit`() = runTest {
        // Given - MB returns exactly 5 candidates, Deezer also has candidates
        val candidates = (1..5).map { i ->
            SearchCandidate("Album $i", "Artist", null, null, null, 90, null, EnrichmentIdentifiers(), "mb")
        }
        val mb = FakeProviderWithSearch(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)), candidates = candidates)
        val deezer = FakeProviderWithSearch(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)), candidates = listOf(
            SearchCandidate("Should Not Appear", "Artist", null, null, null, 75, null, EnrichmentIdentifiers(), "deezer"),
        ))

        // When - searching with limit 5 (primary exactly fills it)
        val results = engine(mb, deezer).search(req, 5)

        // Then - only primary results, no supplemental
        assertEquals(5, results.size)
        assertTrue(results.all { it.provider == "mb" })
    }

    // --- Data-driven needsIdentityResolution ---

    @Test fun `needsIdentityResolution triggers when provider needs MUSICBRAINZ_ID and request lacks it`() = runTest {
        // Given - identity provider + art provider requiring MUSICBRAINZ_ID, request has no MBID
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"))
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-resolved")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds)) }
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching ALBUM_ART with no identifiers
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - identity provider was called (needsIdentityResolution returned true)
        assertTrue("Identity provider should have been called", idProvider.enrichCalls.isNotEmpty())
    }

    @Test fun `needsIdentityResolution skips when all providers use NONE and MBID present`() = runTest {
        // Given - identity provider + art provider with NONE requirement, request has MBID
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val reqWithMbid = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(musicBrainzId = "existing-mbid"), "Test", "Artist")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching ALBUM_ART only (all providers use NONE, MBID present)
        e.enrich(reqWithMbid, setOf(EnrichmentType.ALBUM_ART))

        // Then - identity provider should NOT have been called (MBID present, no provider needs other identifiers)
        assertEquals("Identity provider should not have been called", 0, idProvider.enrichCalls.size)
    }

    @Test fun `needsIdentityResolution skips when required identifiers already present`() = runTest {
        // Given - art provider requires MUSICBRAINZ_ID, request already has MBID
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
        val artProvider = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("caa")) }
        val reqWithMbid = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(musicBrainzId = "existing-mbid"), "OK Computer", "Radiohead")
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching ALBUM_ART with MBID already present
        e.enrich(reqWithMbid, setOf(EnrichmentType.ALBUM_ART))

        // Then - identity provider should NOT have been called (MBID already available)
        assertEquals("Identity provider should not have been called", 0, idProvider.enrichCalls.size)
    }

    // --- Cache key convergence after disambiguation ---

    @Test fun `name-only request caches under name key after identity resolution`() = runTest {
        // Given - identity provider resolves MBID for a name-only request
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"), label = "Parlophone")
        val resolvedIds = EnrichmentIdentifiers(musicBrainzId = "mbid-resolved")
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f, resolvedIdentifiers = resolvedIds))
                it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, metadata, "mb", 0.95f))
            }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), cache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching with name-only request (no MBID)
        val nameReq = EnrichmentRequest.forArtist("Radiohead")
        e.enrich(nameReq, setOf(EnrichmentType.LABEL))

        // Then - result cached under the name-based key (for future name-only lookups)
        val nameKey = DefaultEnrichmentEngine.entityKeyForName(nameReq, EnrichmentType.LABEL)
        assertNotNull("Should be cached under name key", cache.get(nameKey, EnrichmentType.LABEL))
    }

    // --- TTL on EnrichmentType ---

    @Test fun `EnrichmentType ALBUM_ART has 90-day default TTL`() {
        // Given - the ALBUM_ART enrichment type
        // When - reading its default TTL
        // Then - the TTL is 90 days in milliseconds
        assertEquals(7_776_000_000L, EnrichmentType.ALBUM_ART.defaultTtlMs)
    }

    @Test fun `EnrichmentType TRACK_POPULARITY has 7-day default TTL`() {
        // Given - the TRACK_POPULARITY enrichment type
        // When - reading its default TTL
        // Then - the TTL is 7 days in milliseconds
        assertEquals(604_800_000L, EnrichmentType.TRACK_POPULARITY.defaultTtlMs)
    }

    @Test fun `EnrichmentType LABEL has 365-day default TTL`() {
        // Given - the LABEL enrichment type
        // When - reading its default TTL
        // Then - the TTL is 365 days in milliseconds
        assertEquals(31_536_000_000L, EnrichmentType.LABEL.defaultTtlMs)
    }

    @Test fun `EnrichmentType ARTIST_PHOTO has 30-day default TTL`() {
        // Given - the ARTIST_PHOTO enrichment type
        // When - reading its default TTL
        // Then - the TTL is 30 days in milliseconds
        assertEquals(2_592_000_000L, EnrichmentType.ARTIST_PHOTO.defaultTtlMs)
    }

    @Test fun `engine uses ttlOverrides when configured`() = runTest {
        // Given - provider returns successful art result, config has TTL override
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }
        val overrideConfig = EnrichmentConfig(
            enableIdentityResolution = false,
            ttlOverrides = mapOf(EnrichmentType.ALBUM_ART to 999_000L),
        )
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, overrideConfig)

        // When - enriching
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - cache received the overridden TTL
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        assertEquals(999_000L, cache.storedTtls["$key:${EnrichmentType.ALBUM_ART}"])
    }

    @Test fun `engine falls back to type defaultTtlMs without override`() = runTest {
        // Given - provider returns successful art result, no TTL override
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }

        // When - enriching
        engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - cache received the type's default TTL (90 days)
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        assertEquals(EnrichmentType.ALBUM_ART.defaultTtlMs, cache.storedTtls["$key:${EnrichmentType.ALBUM_ART}"])
    }

    // --- Confidence overrides ---

    @Test fun `confidence override replaces provider hardcoded value`() = runTest {
        // Given - iTunes returns 0.65 confidence, config overrides to 0.9
        val p = FakeProvider(id = "itunes", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "itunes", 0.65f)) }
        val overrideConfig = EnrichmentConfig(enableIdentityResolution = false, confidenceOverrides = mapOf("itunes" to 0.9f))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, overrideConfig)

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - confidence is the overridden value
        assertEquals(0.9f, (results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success).confidence)
    }

    @Test fun `confidence override below minConfidence filters result`() = runTest {
        // Given - provider returns 0.8 confidence, but override sets it to 0.3 (below 0.5 threshold)
        val p = FakeProvider(id = "disc", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("url"), "disc", 0.8f)) }
        val overrideConfig = EnrichmentConfig(enableIdentityResolution = false, confidenceOverrides = mapOf("disc" to 0.3f))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, overrideConfig)

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - filtered out as NotFound
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
        // Given - identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - requesting only ARTIST_TIMELINE
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - result is Success with ArtistTimeline containing events
        val timeline = results.raw[EnrichmentType.ARTIST_TIMELINE]
        assertTrue("Expected Success but got $timeline", timeline is EnrichmentResult.Success)
        val data = (timeline as EnrichmentResult.Success).data as EnrichmentData.ArtistTimeline
        val eventTypes = data.events.map { it.type }
        assertTrue("Expected 'formed' event", "formed" in eventTypes)
        assertTrue("Expected 'first_album' event", "first_album" in eventTypes)
        assertTrue("Expected 'member_joined' event", "member_joined" in eventTypes)
    }

    @Test fun `enrich resolves ARTIST_TIMELINE without caller specifying sub-types`() = runTest {
        // Given - identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - requesting only ARTIST_TIMELINE (NOT ARTIST_DISCOGRAPHY or BAND_MEMBERS)
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - only ARTIST_TIMELINE is in the result map; sub-types are not exposed
        assertTrue("ARTIST_TIMELINE should be in results", EnrichmentType.ARTIST_TIMELINE in results.raw)
        assertFalse("ARTIST_DISCOGRAPHY should NOT be in results", EnrichmentType.ARTIST_DISCOGRAPHY in results.raw)
        assertFalse("BAND_MEMBERS should NOT be in results", EnrichmentType.BAND_MEMBERS in results.raw)
    }

    @Test fun `ARTIST_TIMELINE gracefully degrades when sub-types return NotFound`() = runTest {
        // Given - identity metadata with beginDate, but no discography or band members providers
        val idProvider = identityProviderWithMetadata(beginDate = "1985", artistType = "Group")
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - requesting ARTIST_TIMELINE with no sub-type providers
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - still a Success with a partial timeline containing just the life-span event
        val timeline = results.raw[EnrichmentType.ARTIST_TIMELINE]
        assertTrue("Expected Success but got $timeline", timeline is EnrichmentResult.Success)
        val data = (timeline as EnrichmentResult.Success).data as EnrichmentData.ArtistTimeline
        assertEquals(1, data.events.size)
        assertEquals("formed", data.events[0].type)
        assertEquals("1985", data.events[0].date)
    }

    @Test fun `ARTIST_TIMELINE includes sub-type results when caller also requests them`() = runTest {
        // Given - identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - requesting ARTIST_TIMELINE + ARTIST_DISCOGRAPHY explicitly
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE, EnrichmentType.ARTIST_DISCOGRAPHY))

        // Then - both are in the result map
        assertTrue("ARTIST_TIMELINE should be in results", results.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.Success)
        assertTrue("ARTIST_DISCOGRAPHY should be in results", results.raw[EnrichmentType.ARTIST_DISCOGRAPHY] is EnrichmentResult.Success)
    }

    // --- Genre merging via mergeable type path (GENR-02, GENR-03, GENR-04) ---

    private fun genreProviderWithTags(id: String, tags: List<GenreTag>) =
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genreTags = tags), id, 0.9f)) }

    @Test fun `GENRE type merges results from multiple providers`() = runTest {
        // Given - two providers each returning Metadata with different genreTags
        val p1 = genreProviderWithTags("p1", listOf(GenreTag("rock", 0.8f, listOf("p1"))))
        val p2 = genreProviderWithTags("p2", listOf(GenreTag("alternative", 0.7f, listOf("p2")), GenreTag("rock", 0.6f, listOf("p2"))))

        // When - enriching with GENRE
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.GENRE))

        // Then - result is Success with merged genreTags (rock combined from both providers)
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
        // Given - two providers each returning genreTags
        val p1 = genreProviderWithTags("p1", listOf(GenreTag("rock", 0.9f, listOf("p1")), GenreTag("alternative", 0.7f, listOf("p1"))))
        val p2 = genreProviderWithTags("p2", listOf(GenreTag("indie", 0.6f, listOf("p2"))))

        // When - enriching with GENRE
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.GENRE))

        // Then - genres list is populated from top merged tag names
        val result = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        val metadata = result.data as EnrichmentData.Metadata
        assertNotNull("genres list should be populated for backward compatibility", metadata.genres)
        assertTrue("genres should contain rock", "rock" in metadata.genres!!)
        assertTrue("genres should contain alternative", "alternative" in metadata.genres!!)
    }

    @Test fun `GENRE merge uses genre_merger as provider`() = runTest {
        // Given - one provider with genreTags
        val p1 = genreProviderWithTags("p1", listOf(GenreTag("jazz", 0.9f, listOf("p1"))))

        // When - enriching with GENRE
        val results = engine(p1).enrich(req, setOf(EnrichmentType.GENRE))

        // Then - provider field is genre_merger
        val result = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals("genre_merger", result.provider)
    }

    @Test fun `non-GENRE types still short-circuit on first success`() = runTest {
        // Given - two providers both capable of ALBUM_ART; first one succeeds
        val artResult = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x.com/art.jpg"), "p1", 0.95f)
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, artResult) }
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When - enriching with ALBUM_ART
        val results = engine(p1, p2).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - p1 wins, p2 never called (short-circuit preserved)
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("p1", result.provider)
        assertEquals(0, p2.enrichCalls.size)
    }

    @Test fun `ARTIST_TIMELINE returns NotFound for ForAlbum requests`() = runTest {
        // Given - identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - requesting ARTIST_TIMELINE for a ForAlbum request
        val results = e.enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - NotFound because timelines are ForArtist-only
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
        // Given - provider A returns Muse + Bjork, provider B returns Muse + Portishead
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
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When - enriching SIMILAR_ARTISTS with both providers
        val results = e.enrich(artistRequest, setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - result is Success from the merger
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals("similar_artist_merger", result.provider)
        val data = result.data as EnrichmentData.SimilarArtists

        // Then - Muse appears once (deduplicated), with both sources, matchScore capped at 1.0
        val muse = data.artists.first { it.name == "Muse" }
        assertTrue("lastfm" in muse.sources)
        assertTrue("deezer" in muse.sources)
        assertEquals(1.0f, muse.matchScore, 0.001f)

        // Then - Bjork and Portishead each appear once with their original single-provider sources
        val bjork = data.artists.first { it.name == "Bjork" }
        assertEquals(listOf("lastfm"), bjork.sources)
        val portishead = data.artists.first { it.name == "Portishead" }
        assertEquals(listOf("deezer"), portishead.sources)

        // Then - results sorted by matchScore descending (Muse=1.0, Portishead=0.8, Bjork=0.7)
        val scores = data.artists.map { it.matchScore }
        assertEquals(scores, scores.sortedDescending())
    }

    @Test fun `SIMILAR_ARTISTS merge still works when one provider errors`() = runTest {
        // Given - provider A returns artists, provider B throws an exception
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
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When - enriching with one erroring provider
        val results = e.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )

        // Then - still returns merged result from the working provider
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals("similar_artist_merger", result.provider)
        val data = result.data as EnrichmentData.SimilarArtists
        assertEquals(2, data.artists.size)
        assertTrue(data.artists.all { "lastfm" in it.sources })
    }

    @Test fun `SIMILAR_ARTISTS returns Error when all providers error`() = runTest {
        // Given - both providers return errors
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
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When - enriching for SIMILAR_ARTISTS
        val results = e.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )

        // Then - the failures reach the consumer; an empty merge is not a clean absence
        assertTrue(
            "Expected Error but got ${results.raw[EnrichmentType.SIMILAR_ARTISTS]}",
            results.raw[EnrichmentType.SIMILAR_ARTISTS] is EnrichmentResult.Error,
        )
    }

    @Test fun `SIMILAR_ARTISTS merge skips unavailable provider`() = runTest {
        // Given - provider A is available with results, provider B is unavailable
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
            config,
            mergers = listOf(GenreMerger, SimilarArtistMerger),
        )

        // When - enriching for SIMILAR_ARTISTS
        val results = e.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )

        // Then - only available provider's results returned
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        val data = result.data as EnrichmentData.SimilarArtists
        assertEquals(1, data.artists.size)
        assertEquals("Portishead", data.artists[0].name)
        assertEquals(listOf("deezer"), data.artists[0].sources)
        // Unavailable provider should not have been called
        assertEquals(0, providerB.enrichCalls.size)
    }

    @Test fun `timeout backfills missing types with Error TIMEOUT`() = runTest {
        // Given - a slow provider that exceeds the timeout
        val slow = SlowProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            delayMs = 5_000,
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, art("slow")) }
        val shortTimeout = EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100)
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(slow)), cache, shortTimeout)

        // When - enriching with a timeout shorter than the provider's delay
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - timed-out type gets Error with TIMEOUT kind
        val result = results.raw[EnrichmentType.ALBUM_ART]
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (result as EnrichmentResult.Error).errorKind)
        assertEquals("engine", result.provider)
    }

    @Test fun `timeout preserves cached results alongside TIMEOUT errors`() = runTest {
        // Given - one type is cached, another needs a slow provider
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.GENRE), EnrichmentType.GENRE, genre("cached"), CanonicalStatus.RESOLVED)
        val slow = SlowProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            delayMs = 5_000,
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, art("slow")) }
        val shortTimeout = EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100)
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(slow)), cache, shortTimeout)

        // When - requesting both types
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then - cached type returned normally, slow type gets TIMEOUT error
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        val artResult = results.raw[EnrichmentType.ALBUM_ART]
        assertTrue("Expected Error but got $artResult", artResult is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (artResult as EnrichmentResult.Error).errorKind)
    }

    @Test fun `timeout does not cache Error TIMEOUT results`() = runTest {
        // Given - slow provider that will time out
        val slow = SlowProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            delayMs = 5_000,
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, art("slow")) }
        val shortTimeout = EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100)
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(slow)), cache, shortTimeout)

        // When - enriching (will time out)
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - nothing cached (Error results are never cached)
        assertTrue(cache.stored.isEmpty())
    }

    // --- stale cache ---

    private val staleConfig = EnrichmentConfig(
        enableIdentityResolution = false,
        cacheMode = com.landofoz.musicmeta.cache.CacheMode.STALE_IF_ERROR,
    )
    private fun staleEngine(vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, staleConfig)

    @Test fun `STALE_IF_ERROR serves expired cache when provider returns Error`() = runTest {
        // Given - provider returns Error, expiredStore has stale art data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When - enriching with STALE_IF_ERROR config
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - returns expired cache entry with isStale=true
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("stale-provider", result.provider)
        assertTrue("Result should be marked stale", result.isStale)
    }

    @Test fun `STALE_IF_ERROR serves expired cache when provider returns RateLimited`() = runTest {
        // Given - provider returns RateLimited, expiredStore has stale art data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.RateLimited(EnrichmentType.ALBUM_ART, "p")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When - enriching with STALE_IF_ERROR config
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - returns expired cache entry with isStale=true
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("stale-provider", result.provider)
        assertTrue("Result should be marked stale", result.isStale)
    }

    @Test fun `STALE_IF_ERROR does not serve stale for genuine NotFound`() = runTest {
        // Given - provider returns NotFound, expiredStore has stale art data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        // FakeProvider returns NotFound by default when no result is configured
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When - enriching with STALE_IF_ERROR config
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - NotFound is preserved, stale cache NOT served (provider searched and found nothing)
        assertTrue(
            "Expected NotFound but got ${results.raw[EnrichmentType.ALBUM_ART]}",
            results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound,
        )
    }

    @Test fun `NETWORK_FIRST does not serve stale when provider fails`() = runTest {
        // Given - default NETWORK_FIRST config, provider returns Error, expiredStore has stale data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When - enriching with default NETWORK_FIRST config (uses engine(), not staleEngine())
        val results = engine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - Error returned, stale fallback NOT applied
        assertTrue(
            "Expected Error but got ${results.raw[EnrichmentType.ALBUM_ART]}",
            results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Error,
        )
    }

    @Test fun `stale result is not re-written to cache`() = runTest {
        // Given - provider returns Error, expiredStore has stale art data, stored is empty
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When - enriching with STALE_IF_ERROR (stale is served)
        staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - stale result was NOT re-cached with a fresh TTL (stored remains empty)
        assertTrue("Stale result should not be written to fresh cache", cache.stored.isEmpty())
    }

    // --- v0.8.0 edge cases ---

    @Test fun `STALE_IF_ERROR serves stale on timeout Error`() = runTest {
        // Given - timeout produces Error(TIMEOUT), expiredStore has stale data
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "timed out", errorKind = ErrorKind.TIMEOUT)) }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-provider")

        // When - enriching with STALE_IF_ERROR
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - stale served even for TIMEOUT errors (TIMEOUT is ErrorKind on Error)
        val result = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals("stale-provider", result.provider)
        assertTrue("Result should be marked stale", result.isStale)
    }

    @Test fun `STALE_IF_ERROR preserves Error when no expired entry exists`() = runTest {
        // Given - provider returns Error, but NO expired entry in cache
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down")) }
        // expiredStore is empty — no stale data available

        // When - enriching with STALE_IF_ERROR
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - Error is preserved because there's nothing stale to serve
        assertTrue(
            "Expected Error when no expired entry exists, got ${results.raw[EnrichmentType.ALBUM_ART]}",
            results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Error,
        )
    }

    @Test fun `STALE_IF_ERROR mixed types - Error gets stale, Success stays fresh`() = runTest {
        // Given - two types: art=Error (has stale), genre=Success (fresh from provider)
        val p = FakeProvider(
            id = "p",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ALBUM_ART, 100),
                ProviderCapability(EnrichmentType.GENRE, 100),
            ),
        ).also {
            it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down"))
            it.givenResult(EnrichmentType.GENRE, genre("fresh-genre"))
        }
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] = art("stale-art")

        // When - enriching both types with STALE_IF_ERROR
        val results = staleEngine(p).enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then - art gets stale fallback, genre stays fresh
        val artResult = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertTrue("Art should be stale", artResult.isStale)
        assertEquals("stale-art", artResult.provider)

        val genreResult = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertFalse("Genre should NOT be stale", genreResult.isStale)
        assertEquals("fresh-genre", genreResult.provider)
    }

    @Test fun `ARTIST_TIMELINE is cached like standard types`() = runTest {
        // Given - identity provider + discography + band members providers
        val idProvider = identityProviderWithMetadata()
        val discoProvider = discographyProvider()
        val membersProvider = bandMembersProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, discoProvider, membersProvider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - first enrich call
        e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))
        val discoCallsAfterFirst = discoProvider.enrichCalls.size

        // When - second enrich call for the same request
        val results = e.enrich(artistReq, setOf(EnrichmentType.ARTIST_TIMELINE))

        // Then - ARTIST_TIMELINE is returned from cache; discography provider not called again
        assertTrue("ARTIST_TIMELINE should be Success on second call", results.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.Success)
        assertEquals("Discography provider should not be called again on cache hit", discoCallsAfterFirst, discoProvider.enrichCalls.size)
    }

    // --- transient side-lookup must not resolve to a cacheable NotFound ---

    @Test fun `a transient in identity resolution reclassifies an identifier-gated type to Error even when a same-chain provider with no requirement ran and returned its own NotFound`() = runTest {
        // Given - identity resolution throws a transient (mirrors MusicBrainz hiccuping); the target
        // type's chain has one provider requiring WIKIPEDIA_TITLE (skipped — never called) and one
        // requiring nothing (Last.fm-shaped — eligible, runs, returns its own genuine NotFound).
        // This is ALBUM_DESCRIPTION's real chain shape and the exact scenario the design review
        // flagged: the chain's own resolve() collapses to NotFound("all_providers") because the
        // second provider ran, so any fix keyed off that return value alone would miss this case.
        val idProvider = ThrowingIdentityProvider("mb")
        val wikipediaLike = FakeProvider(
            id = "wikipedia",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 100, identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE)),
        )
        val lastfmLike = FakeProvider(
            id = "lastfm",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 50)),
        ).also { it.givenResult(EnrichmentType.ALBUM_DESCRIPTION, EnrichmentResult.NotFound(EnrichmentType.ALBUM_DESCRIPTION, "lastfm")) }
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, wikipediaLike, lastfmLike)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - enriching a request with no pre-existing MBID
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_DESCRIPTION))

        // Then - the chain behaved exactly as it does on main (Last.fm ran, Wikipedia skipped)...
        assertEquals(1, lastfmLike.enrichCalls.size)
        assertEquals(0, wikipediaLike.enrichCalls.size)
        // ...but the type's final result is Error, not a cacheable NotFound
        assertTrue(
            "expected Error, got ${results.raw[EnrichmentType.ALBUM_DESCRIPTION]}",
            results.raw[EnrichmentType.ALBUM_DESCRIPTION] is EnrichmentResult.Error,
        )

        // And — nothing was cached for it (Error is never cached)
        assertTrue(cache.stored.keys.none { it.endsWith(":${EnrichmentType.ALBUM_DESCRIPTION}") })
    }

    @Test fun `a transient reclassifies a merger-consumed type too`() = runTest {
        // Given - production wiring merges ARTIST_PHOTO via ArtworkMerger (EnrichmentEngine.kt),
        // which produces its own NotFound on an empty resolveAll() rather than going through
        // ProviderChain.resolve() at all.
        val idProvider = ThrowingIdentityProvider("mb")
        val wikidataLike = FakeProvider(
            id = "wikidata",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_PHOTO, 100, identifierRequirement = IdentifierRequirement.WIKIDATA_ID)),
        )
        val wikipediaLike = FakeProvider(
            id = "wikipedia",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_PHOTO, 90, identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE)),
        )
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, wikidataLike, wikipediaLike)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
            mergers = listOf(GenreMerger, ArtworkMerger(EnrichmentType.ARTIST_PHOTO)),
        )

        // When - enriching for ARTIST_PHOTO
        val results = e.enrich(req, setOf(EnrichmentType.ARTIST_PHOTO))

        // Then - both providers skipped (neither identifier ever resolved), merger's own
        // NotFound("all_providers") reclassified to Error
        assertEquals(0, wikidataLike.enrichCalls.size)
        assertEquals(0, wikipediaLike.enrichCalls.size)
        assertTrue(
            "expected Error, got ${results.raw[EnrichmentType.ARTIST_PHOTO]}",
            results.raw[EnrichmentType.ARTIST_PHOTO] is EnrichmentResult.Error,
        )
    }

    @Test fun `no reclassification when the identifier gap is permanent, not transient`() = runTest {
        // Given - same shape as the BLOCKING-defect probe, but identity resolution succeeds this
        // time with a Success carrying no wiki identifiers (a genuine, non-transient absence) —
        // Forbidden State #4: must stay NotFound.
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true)
            .also {
                it.givenIdentityResult(
                    EnrichmentResult.Success(
                        EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.9f,
                        resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
                    ),
                )
            }
        val wikipediaLike = FakeProvider(
            id = "wikipedia",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 100, identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE)),
        )
        val lastfmLike = FakeProvider(
            id = "lastfm",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 50)),
        ).also { it.givenResult(EnrichmentType.ALBUM_DESCRIPTION, EnrichmentResult.NotFound(EnrichmentType.ALBUM_DESCRIPTION, "lastfm")) }
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, wikipediaLike, lastfmLike)),
            cache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - enriching for ALBUM_DESCRIPTION
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_DESCRIPTION))

        // Then - still NotFound; no transient fired this run
        assertTrue(results.raw[EnrichmentType.ALBUM_DESCRIPTION] is EnrichmentResult.NotFound)
    }

    // --- negative caching ---

    @Test fun `fan-out NotFound is negative-cached and served without a re-ask`() = runTest {
        // Given - a provider that always NotFounds, backed by a real in-memory cache
        val negCache = InMemoryEnrichmentCache()
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config)

        // When - enriching twice inside the negative TTL window
        val first = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the provider was asked only once, and the served result is identical, live or cached
        assertEquals(1, p.enrichCalls.size)
        assertTrue(second.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
        assertEquals(first.raw[EnrichmentType.ALBUM_ART], second.raw[EnrichmentType.ALBUM_ART])
    }

    @Test fun `expired negative is re-asked`() = runTest {
        // Given - a short negative TTL and a provider that always NotFounds
        var time = 0L
        val negCache = InMemoryEnrichmentCache(clock = { time })
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config.copy(negativeTtlMs = 1000))
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // When - the clock advances past the negative TTL and the type is enriched again
        time += 1500
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the provider was asked on both calls
        assertEquals(2, p.enrichCalls.size)
    }

    @Test fun `a cache-hit negative served alongside a miss does not slide its TTL`() = runTest {
        // Given - type ALBUM_ART already negative-cached with a short TTL, type GENRE uncached
        var time = 0L
        val negCache = InMemoryEnrichmentCache(clock = { time })
        val keyA = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        negCache.putNegative(keyA, EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers"), CanonicalStatus.RESOLVED, 1000)
        val p = FakeProvider(
            id = "p",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100), ProviderCapability(EnrichmentType.GENRE, 100)),
        )
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config.copy(negativeTtlMs = 1000))

        // When - enriching before ALBUM_ART's original expiry (a cache hit alongside GENRE's
        // miss, which forces a write-back), then again after that original expiry
        time = 800
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))
        time = 1200
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then - the mixed call at time 800 did not extend ALBUM_ART's TTL, so it expired on
        // schedule and the provider is re-asked for it
        assertTrue(p.enrichCalls.any { it.second == EnrichmentType.ALBUM_ART })
        assertTrue(second.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
    }

    @Test fun `forceRefresh bypasses and clears a negative entry`() = runTest {
        // Given - a negative already cached, and a provider now able to answer
        val negCache = InMemoryEnrichmentCache()
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config)
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        p.givenResult(EnrichmentType.ALBUM_ART, art("p"))

        // When - forcing a refresh, then enriching again without forceRefresh
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true)
        val after = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - forceRefresh re-asked the provider despite the cached negative, and the fresh
        // Success is served afterwards rather than a leftover negative shadowing it
        assertEquals(2, p.enrichCalls.size)
        assertTrue(after.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
    }

    @Test fun `invalidate clears a negative entry`() = runTest {
        // Given - a negative cached for the type
        val negCache = InMemoryEnrichmentCache()
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config)
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // When - invalidating the type, then enriching again
        e.invalidate(req, EnrichmentType.ALBUM_ART)
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the provider was asked on both calls: invalidate cleared the cached negative
        assertEquals(2, p.enrichCalls.size)
    }

    @Test fun `Success results still round-trip through the cache unaffected by negative caching`() = runTest {
        // Given - a provider that succeeds, backed by a real in-memory cache
        val negCache = InMemoryEnrichmentCache()
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config)

        // When - enriching twice
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the provider was asked only once; the Success was served from cache on the repeat
        assertEquals(1, p.enrichCalls.size)
    }

    @Test fun `STALE_IF_ERROR distinguishes an expired negative from an expired positive`() = runTest {
        // Given - real cache holding an expired negative for art and an expired Success for genre,
        // with a provider that now errors on both
        var time = 0L
        val negCache = InMemoryEnrichmentCache(clock = { time })
        val shortTtlConfig = EnrichmentConfig(
            enableIdentityResolution = false,
            cacheMode = com.landofoz.musicmeta.cache.CacheMode.STALE_IF_ERROR,
            negativeTtlMs = 1000,
            ttlOverrides = mapOf(EnrichmentType.GENRE to 1000L),
        )
        val p = FakeProvider(
            id = "p",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ALBUM_ART, 100),
                ProviderCapability(EnrichmentType.GENRE, 100),
            ),
        ).also { it.givenResult(EnrichmentType.GENRE, genre("p")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, shortTtlConfig)
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))
        time += 1500
        p.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "down"))
        p.givenResult(EnrichmentType.GENRE, EnrichmentResult.Error(EnrichmentType.GENRE, "p", "down"))

        // When - enriching again with both entries expired and the provider now erroring
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE))

        // Then - the expired negative never resurrects as NotFound, but the expired Success still
        // serves stale — the positive stale path is untouched
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Error)
        val genreResult = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertTrue(genreResult.isStale)
    }

    @Test fun `a NotFound with suggestions is not negative-cached`() = runTest {
        // Given - identity provider returns NotFound with suggestions, backed by a real cache
        val negCache = InMemoryEnrichmentCache()
        val suggestions = listOf(
            SearchCandidate("Bush", null, "1992", "GB", "Group", 75, null, EnrichmentIdentifiers(musicBrainzId = "mbid-gb"), "mb", disambiguation = "British rock band"),
        )
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = suggestions)) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider)), negCache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching with identity resolution that fails with suggestions
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - nothing was negative-cached for the type
        assertNull(negCache.getNegative(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
    }

    @Test fun `a NotFound under a SUGGESTIONS canonical outcome is not negative-cached`() = runTest {
        // Given - a plain fan-out NotFound and a call whose canonical resolution ended SUGGESTIONS
        val e = engine()
        val result = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers")

        // When - checking whether the result is cacheable as a negative
        val cacheable = e.isCacheableNegative(result, CanonicalStatus.AMBIGUOUS, identifierIncomplete = false)

        // Then - an unresolved canonical envelope blocks negative caching for every type of the call
        assertFalse(cacheable)
    }

    @Test fun `a NotFound under an UNVERIFIED canonical outcome is not negative-cached`() = runTest {
        // Given - a plain fan-out NotFound and a call whose canonical resolution errored
        val e = engine()
        val result = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers")

        // When - checking whether the result is cacheable as a negative
        val cacheable = e.isCacheableNegative(result, CanonicalStatus.FAILED, identifierIncomplete = false)

        // Then - an identity outage blocks negative caching the same way an unresolved name does
        assertFalse(cacheable)
    }

    @Test fun `an identifier-incomplete NotFound under a RESOLVED identity is not negative-cached`() = runTest {
        // Given - a chain that skipped a provider for a missing identifier, under a call whose
        // canonical resolution otherwise succeeded
        val e = engine()
        val result = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers")

        // When - checking whether the result is cacheable as a negative
        val cacheable = e.isCacheableNegative(result, CanonicalStatus.RESOLVED, identifierIncomplete = true)

        // Then - a provider that was never asked cannot speak for the chain, resolved identity or not
        assertFalse(cacheable)
    }

    @Test fun `a complete exhausted chain under a RESOLVED identity remains negative-cacheable`() = runTest {
        // Given - every eligible provider ran and found nothing, under a resolved canonical identity
        val e = engine()
        val result = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers")

        // When - checking whether the result is cacheable as a negative
        val cacheable = e.isCacheableNegative(result, CanonicalStatus.RESOLVED, identifierIncomplete = false)

        // Then - today's confident-negative behavior is unchanged
        assertTrue(cacheable)
    }

    @Test fun `a NotFound with no canonical resolution attempted remains negative-cacheable`() = runTest {
        // Given - a type whose chain needed no identity resolution at all
        val e = engine()
        val result = EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "all_providers")

        // When - checking whether the result is cacheable as a negative
        val cacheable = e.isCacheableNegative(result, CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED, identifierIncomplete = false)

        // Then - "not attempted" is as confident as a resolved match
        assertTrue(cacheable)
    }

    @Test fun `a BEST_EFFORT Success is re-fetched on the second call and never cached`() = runTest {
        // Given - identity fails without suggestions and a NONE provider succeeds best-effort
        val realCache = InMemoryEnrichmentCache()
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb")) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), realCache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching twice
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - nothing was cached, so the second call re-asked the provider and stayed BEST_EFFORT
        assertEquals(2, artProvider.enrichCalls.size)
        val success = second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(CanonicalStatus.UNRESOLVED, second.identity.status)
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
    }

    @Test fun `a complete NotFound under SUGGESTIONS is re-fetched and preserves suggestions on the second call`() = runTest {
        // Given - identity fails with suggestions and the only provider is a genuine, complete miss
        val realCache = InMemoryEnrichmentCache()
        val suggestions = listOf(
            SearchCandidate("Bush", null, "1992", "GB", "Group", 75, null, EnrichmentIdentifiers(musicBrainzId = "mbid-gb"), "mb", disambiguation = "British rock band"),
        )
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = suggestions)) }
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), realCache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching twice
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the second call re-asked the provider and the suggestions are still there
        assertEquals(2, artProvider.enrichCalls.size)
        assertEquals(suggestions, second.identity.suggestions)
    }

    @Test fun `a complete NotFound under UNVERIFIED is re-fetched rather than cached as a confident absence`() = runTest {
        // Given - identity resolution throws (UNVERIFIED) and the only provider is a genuine miss
        val realCache = InMemoryEnrichmentCache()
        val idProvider = ThrowingIdentityProvider("mb")
        val artProvider = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, artProvider)), realCache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching twice
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the outage never became a confident cached "no data" answer
        assertEquals(2, artProvider.enrichCalls.size)
        assertEquals(CanonicalStatus.FAILED, second.identity.status)
    }

    @Test fun `an identifier-incomplete NotFound is re-fetched and absent from the negative cache`() = runTest {
        // Given - a RESOLVED identity, and a chain that skips one provider for a missing identifier
        // while another eligible provider genuinely finds nothing
        val realCache = InMemoryEnrichmentCache()
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(
                    EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = emptyList()), "mb", 1f),
                )
            }
        val deezer = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "deezer")) }
        val wikidataOnly = FakeProvider(
            id = "wikidata",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 30, identifierRequirement = IdentifierRequirement.WIKIDATA_ID)),
        )
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, deezer, wikidataOnly)), realCache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching twice
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the second call re-asked deezer instead of serving a confident cached negative
        assertEquals(2, deezer.enrichCalls.size)
        assertTrue(second.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.NotFound)
        assertNull(realCache.getNegative(DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART), EnrichmentType.ALBUM_ART))
    }

    @Test fun `a complete exhausted NotFound under RESOLVED identity is still negative-cached`() = runTest {
        // Given - a RESOLVED identity and every eligible provider genuinely finding nothing
        val realCache = InMemoryEnrichmentCache()
        val idProvider = FakeProvider(id = "mb", isIdentityProvider = true, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also {
                it.givenIdentityResult(
                    EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(genres = emptyList()), "mb", 1f),
                )
            }
        val deezer = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "deezer")) }
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(idProvider, deezer)), realCache, EnrichmentConfig(enableIdentityResolution = true))

        // When - enriching twice
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - today's confident negative-cache behavior is unchanged: only one live call
        assertEquals(1, deezer.enrichCalls.size)
    }

    @Test fun `a transient-reclassified NotFound is not negative-cached`() = runTest {
        // Given - identity resolution throws a transient; the target type's chain has a
        // same-chain provider with no identifier requirement that ran and returned its own
        // genuine NotFound, so the chain's own result collapses to NotFound("all_providers")
        val negCache = InMemoryEnrichmentCache()
        val idProvider = ThrowingIdentityProvider("mb")
        val wikipediaLike = FakeProvider(
            id = "wikipedia",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 100, identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE)),
        )
        val lastfmLike = FakeProvider(
            id = "lastfm",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 50)),
        ).also { it.givenResult(EnrichmentType.ALBUM_DESCRIPTION, EnrichmentResult.NotFound(EnrichmentType.ALBUM_DESCRIPTION, "lastfm")) }
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(idProvider, wikipediaLike, lastfmLike)),
            negCache,
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - enriching a request with no pre-existing MBID
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_DESCRIPTION))

        // Then - the type resolved to Error (reclassified from the chain's own NotFound), and
        // writeBack saw only that Error, so nothing was negative-cached
        assertTrue(results.raw[EnrichmentType.ALBUM_DESCRIPTION] is EnrichmentResult.Error)
        assertNull(
            negCache.getNegative(
                DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_DESCRIPTION),
                EnrichmentType.ALBUM_DESCRIPTION,
            ),
        )
    }

    @Test fun `forceRefresh negative write-back reaches a manually-selected key already cleared by invalidate`() = runTest {
        // Given - a manually-selected Success cached for the type
        val negCache = InMemoryEnrichmentCache()
        val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        negCache.put(key, EnrichmentType.ALBUM_ART, art("manual"), CanonicalStatus.RESOLVED)
        negCache.markManuallySelected(key, EnrichmentType.ALBUM_ART)
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val e = DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), negCache, config)

        // When - forcing a refresh while the provider now has nothing
        e.enrich(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true)

        // Then - forceRefresh's own invalidate already cleared the manually-selected Success the
        // same way it clears any other entry, so the negative write-back lands on an empty slot;
        // the manual flag is consumer-facing display metadata, not a write-back exemption
        assertNull(negCache.get(key, EnrichmentType.ALBUM_ART))
        assertNotNull(negCache.getNegative(key, EnrichmentType.ALBUM_ART))
    }
}

/** Identity provider whose [resolveIdentity] throws a transient — exercises the `catch` in
 *  `DefaultEnrichmentEngine.resolveIdentity`, not a returned `Error`/`NotFound`. */
private class ThrowingIdentityProvider(id: String) : FakeProvider(id = id, isIdentityProvider = true) {
    override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult =
        throw java.io.IOException("simulated transient")
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
