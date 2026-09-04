package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [CanonicalStatus] (once per call) and [LookupProvenance] (once per successful result) are
 * independent facts: an orthogonality matrix over both, and every non-attempted status path pinned
 * to a distinct, explicit [CanonicalStatus] value.
 */
class CanonicalStatusModelTest {

    private val suggestions = listOf(
        SearchCandidate(
            "Bush", null, "1992", "GB", "Group", 75, null,
            EnrichmentIdentifiers(musicBrainzId = "mbid-gb"), "mb", disambiguation = "British rock band",
        ),
    )

    private fun idProvider(result: EnrichmentResult) = FakeProvider(
        id = "mb", isIdentityProvider = true,
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
    ).also { it.givenIdentityResult(result) }

    private fun artProvider() = FakeProvider(
        id = "deezer",
        capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
    ).also {
        it.givenResult(
            EnrichmentType.TRACK_PREVIEW,
            EnrichmentResult.Success(
                EnrichmentType.TRACK_PREVIEW,
                com.landofoz.musicmeta.EnrichmentData.TrackPreview(url = "https://x/preview.mp3", source = "deezer", durationMs = 30_000),
                "deezer",
                0.8f,
            ),
        )
    }

    /**
     * [artProvider] with an optional identifier requirement (like the real Deezer provider's), that
     * self-reports having taken this call's id branch — the only source of truth for that, since its
     * capability alone does not require an id and a request carrying one proves nothing about
     * whether this particular provider consumed it.
     */
    private fun artProviderById() = FakeProvider(
        id = "deezer",
        capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
    ).also {
        it.givenResult(
            EnrichmentType.TRACK_PREVIEW,
            EnrichmentResult.Success(
                EnrichmentType.TRACK_PREVIEW,
                com.landofoz.musicmeta.EnrichmentData.TrackPreview(url = "https://x/preview.mp3", source = "deezer", durationMs = 30_000),
                "deezer",
                0.8f,
                provenance = LookupProvenance.PROVIDER_NATIVE_ID,
            ),
        )
    }

    private fun engine(cache: com.landofoz.musicmeta.EnrichmentCache, vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, EnrichmentConfig(enableIdentityResolution = true))

    // --- Orthogonality: canonical status x lookup provenance ---

    private fun mbidRequiredArtProvider() = FakeProvider(
        id = "caa",
        capabilities = listOf(
            ProviderCapability(
                EnrichmentType.ALBUM_ART, 100,
                identifierRequirement = com.landofoz.musicmeta.IdentifierRequirement.MUSICBRAINZ_ID,
            ),
        ),
    ).also {
        it.givenResult(
            EnrichmentType.ALBUM_ART,
            EnrichmentResult.Success(EnrichmentType.ALBUM_ART, com.landofoz.musicmeta.EnrichmentData.Artwork("https://x/art.jpg"), "caa", 0.9f),
        )
    }

    @Test fun `canonical resolved plus a canonical-id lookup yields CANONICAL_ID provenance`() = runTest {
        // Given - identity resolves by name, handing the downstream MBID-required provider an id
        // the request itself never carried
        val mb = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")),
                "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
            ),
        )
        val art = mbidRequiredArtProvider()
        val e = engine(FakeEnrichmentCache(), mb, art)
        val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - resolved canonically, and the downstream lookup used that canonical id
        assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
        val success = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, success.provenance)
    }

    @Test fun `canonical resolved plus an exact-name provider lookup yields EXACT_NAME provenance`() = runTest {
        // Given - identity resolves by name search (no MBID on the request itself), reporting the
        // name route it took
        val mb = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")),
                "mb", 0.9f, resolvedIdentifiers = null, provenance = LookupProvenance.EXACT_NAME,
            ),
        )
        val lyrics = FakeProvider(id = "lrclib", capabilities = listOf(ProviderCapability(EnrichmentType.LYRICS_PLAIN, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.LYRICS_PLAIN,
                    EnrichmentResult.Success(EnrichmentType.LYRICS_PLAIN, com.landofoz.musicmeta.EnrichmentData.Lyrics(plainLyrics = "la la"), "lrclib", 0.8f),
                )
            }
        val e = engine(FakeEnrichmentCache(), mb, lyrics)
        val req = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.LYRICS_PLAIN))

        // Then - the name-keyed lookup is confirmed exact, not fuzzy, because canonical resolved
        assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
        val success = results.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.Success
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
    }

    @Test fun `an MBID resolution filled onto the request does not relabel a name-only provider's search as canonical`() = runTest {
        // Given - identity matches the caller's name and resolves an MBID from it, but the only
        // provider serving this type never accepts one — it can only have searched by name,
        // whatever the request now carries
        val mb = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")),
                "mb", 0.9f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
                provenance = LookupProvenance.EXACT_NAME,
            ),
        )
        val lyrics = FakeProvider(id = "lrclib", capabilities = listOf(ProviderCapability(EnrichmentType.LYRICS_PLAIN, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.LYRICS_PLAIN,
                    EnrichmentResult.Success(EnrichmentType.LYRICS_PLAIN, com.landofoz.musicmeta.EnrichmentData.Lyrics(plainLyrics = "la la"), "lrclib", 0.8f),
                )
            }
        val e = engine(FakeEnrichmentCache(), mb, lyrics)
        val req = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.LYRICS_PLAIN))

        // Then - the request now carries an MBID, but LRCLIB never consumes one, so its route is
        // still the exact name search MusicBrainz confirmed, never the canonical id it never touched
        assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
        val success = results.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.Success
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
        assertNotEquals(LookupProvenance.CANONICAL_ID, success.provenance)
    }

    @Test fun `ambiguous canonical status distinguishes provider-id lookup from fuzzy-name lookup`() = runTest {
        // Given - identity fails with suggestions (AMBIGUOUS), one type reached by a trusted Deezer
        // track id on the request, the other reached only by name
        val mb = idProvider(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = suggestions))
        val lyrics = FakeProvider(id = "lrclib", capabilities = listOf(ProviderCapability(EnrichmentType.LYRICS_PLAIN, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.LYRICS_PLAIN,
                    EnrichmentResult.Success(EnrichmentType.LYRICS_PLAIN, com.landofoz.musicmeta.EnrichmentData.Lyrics(plainLyrics = "la la"), "lrclib", 0.7f),
                )
            }
        val deezer = artProviderById()
        val e = engine(
            FakeEnrichmentCache(), mb, lyrics, deezer,
        )
        val req = EnrichmentRequest.forTrack(
            "Song", "Artist",
            identifiers = EnrichmentIdentifiers().with(com.landofoz.musicmeta.IdentifierNamespace.DEEZER, "deezer-track-1"),
        )

        // When - enriching both a provider-id-eligible type and a name-only type
        val results = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW, EnrichmentType.LYRICS_PLAIN))

        // Then - both are best-effort at the call level, but their provenance is distinguishable
        assertEquals(CanonicalStatus.AMBIGUOUS, results.identity.status)
        val preview = results.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        val plainLyrics = results.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.Success
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, preview.provenance)
        assertEquals(LookupProvenance.FUZZY_NAME, plainLyrics.provenance)
        assertNotEquals(preview.provenance, plainLyrics.provenance)
    }

    @Test fun `failed canonical status still reports PROVIDER_NATIVE_ID for an exact provider-id lookup`() = runTest {
        // Given - identity resolution errors (FAILED), but the request carries a trusted Deezer id
        val mb = idProvider(EnrichmentResult.Error(EnrichmentType.GENRE, "mb", "boom"))
        val deezer = artProviderById()
        val e = engine(FakeEnrichmentCache(), mb, deezer)
        val req = EnrichmentRequest.forTrack(
            "Song", "Artist",
            identifiers = EnrichmentIdentifiers().with(com.landofoz.musicmeta.IdentifierNamespace.DEEZER, "deezer-track-1"),
        )

        // When - enriching the provider-id-eligible type
        val results = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - canonical resolution failed, but the exact id lookup is still reported as such
        assertEquals(CanonicalStatus.FAILED, results.identity.status)
        val preview = results.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, preview.provenance)
    }

    @Test fun `disabled resolution plus a provider-id lookup yields PROVIDER_NATIVE_ID`() = runTest {
        // Given - identity resolution disabled entirely, request carries a trusted Deezer id
        val deezer = artProviderById()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(deezer)), FakeEnrichmentCache(), EnrichmentConfig(enableIdentityResolution = false),
        )
        val req = EnrichmentRequest.forTrack(
            "Song", "Artist",
            identifiers = EnrichmentIdentifiers().with(com.landofoz.musicmeta.IdentifierNamespace.DEEZER, "deezer-track-1"),
        )

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - not attempted because disabled, and the id-keyed lookup is still provider-native
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, results.identity.status)
        val preview = results.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, preview.provenance)
    }

    @Test fun `disabled resolution plus a name-only lookup yields FUZZY_NAME`() = runTest {
        // Given - identity resolution disabled entirely, and the request names an entity but no id
        val deezer = artProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(deezer)), FakeEnrichmentCache(), EnrichmentConfig(enableIdentityResolution = false),
        )
        val req = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - not attempted because disabled, and the name-only lookup is unverified
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, results.identity.status)
        val preview = results.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, preview.provenance)
    }

    @Test fun `a merged result reports the weakest contributing provenance, not canonical status alone`() = runTest {
        // Given - identity resolves canonically, and two GENRE providers each self-report a
        // different route: one used the canonical id resolution just handed it, the other only an
        // unverified name search
        val mb = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")),
                "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
            ),
        )
        val canonicalContributor = FakeProvider(
            id = "canonical",
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.GENRE,
                EnrichmentResult.Success(
                    EnrichmentType.GENRE,
                    com.landofoz.musicmeta.EnrichmentData.Metadata(
                        genreTags = listOf(com.landofoz.musicmeta.GenreTag("rock", 0.8f, listOf("canonical"))),
                    ),
                    "canonical", 0.8f, provenance = LookupProvenance.CANONICAL_ID,
                ),
            )
        }
        val nameContributor = FakeProvider(
            id = "names",
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 90)),
        ).also {
            it.givenResult(
                EnrichmentType.GENRE,
                EnrichmentResult.Success(
                    EnrichmentType.GENRE,
                    com.landofoz.musicmeta.EnrichmentData.Metadata(
                        genreTags = listOf(com.landofoz.musicmeta.GenreTag("alternative", 0.7f, listOf("names"))),
                    ),
                    "names", 0.7f, provenance = LookupProvenance.FUZZY_NAME,
                ),
            )
        }
        val e = engine(FakeEnrichmentCache(), mb, canonicalContributor, nameContributor)
        val req = EnrichmentRequest.forArtist("Artist")

        // When - enriching GENRE, which GenreMerger combines from both contributors
        val results = e.enrich(req, setOf(EnrichmentType.GENRE))

        // Then - canonical status alone would say EXACT_NAME for every result this call produced;
        // the merged result instead reports its weakest contributing route, since the name-search
        // contributor's tag was never canonically confirmed
        assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
        val merged = results.raw.getValue(EnrichmentType.GENRE) as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, merged.provenance)
    }

    // --- Cache round trips: RESOLVED and disabled results are cacheable and preserve provenance ---

    @Test fun `a RESOLVED canonical-id result round-trips through cache with the same provenance and no confidence gain`() = runTest {
        // Given - a real cache, an identity provider that resolves once
        val cache = InMemoryEnrichmentCache()
        val mb = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")),
                "mb", 0.95f, resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
            ),
        )
        val art = mbidRequiredArtProvider()
        val e = engine(cache, mb, art)
        val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching twice: the first is live, the second is an all-cache-hit
        val first = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))
        val second = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the first call was live and RESOLVED; the second is an honest cache hit that never
        // claims RESOLVED for itself, while the underlying result's provenance is unchanged
        assertEquals(CanonicalStatus.RESOLVED, first.identity.status)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, second.identity.status)
        val firstSuccess = first.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        val secondSuccess = second.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, firstSuccess.provenance)
        assertEquals(LookupProvenance.CANONICAL_ID, secondSuccess.provenance)
        assertEquals(1, art.enrichCalls.size)
    }

    @Test fun `a RESOLVED exact-name result round-trips through cache with the same provenance and no confidence gain`() = runTest {
        // Given - a real cache, an identity provider that resolves by name (no MBID) and reports
        // that route, and a name-only provider serving the type
        val cache = InMemoryEnrichmentCache()
        val mb = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")),
                "mb", 0.9f, resolvedIdentifiers = null, provenance = LookupProvenance.EXACT_NAME,
            ),
        )
        val lyrics = FakeProvider(id = "lrclib", capabilities = listOf(ProviderCapability(EnrichmentType.LYRICS_PLAIN, 100)))
            .also {
                it.givenResult(
                    EnrichmentType.LYRICS_PLAIN,
                    EnrichmentResult.Success(EnrichmentType.LYRICS_PLAIN, com.landofoz.musicmeta.EnrichmentData.Lyrics(plainLyrics = "la la"), "lrclib", 0.8f),
                )
            }
        val e = engine(cache, mb, lyrics)
        val req = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching twice: the first is live, the second is an all-cache-hit
        val first = e.enrich(req, setOf(EnrichmentType.LYRICS_PLAIN))
        val second = e.enrich(req, setOf(EnrichmentType.LYRICS_PLAIN))

        // Then - both calls report the same exact-name provenance; the cache hit claims no more
        // confidence at the call level than an honest cache hit is owed
        assertEquals(CanonicalStatus.RESOLVED, first.identity.status)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, second.identity.status)
        val firstSuccess = first.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.Success
        val secondSuccess = second.raw[EnrichmentType.LYRICS_PLAIN] as EnrichmentResult.Success
        assertEquals(LookupProvenance.EXACT_NAME, firstSuccess.provenance)
        assertEquals(LookupProvenance.EXACT_NAME, secondSuccess.provenance)
    }

    @Test fun `a disabled-resolution provider-id result round-trips through cache with the same provenance`() = runTest {
        // Given - identity resolution disabled entirely, a request carrying a trusted Deezer id
        val cache = InMemoryEnrichmentCache()
        val deezer = artProviderById()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(deezer)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )
        val req = EnrichmentRequest.forTrack(
            "Song", "Artist",
            identifiers = EnrichmentIdentifiers().with(com.landofoz.musicmeta.IdentifierNamespace.DEEZER, "deezer-track-1"),
        )

        // When - enriching twice: the first is live, the second is an all-cache-hit
        val first = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))
        val second = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - both calls report the same provider-native provenance; disabled identity
        // resolution outranks the cache-hit reason on both the live and the cached call
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, first.identity.status)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, second.identity.status)
        val firstSuccess = first.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        val secondSuccess = second.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, firstSuccess.provenance)
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, secondSuccess.provenance)
    }

    @Test fun `a disabled-resolution name-only result round-trips through cache with the same provenance`() = runTest {
        // Given - identity resolution disabled entirely, and the request names an entity but no id
        val cache = InMemoryEnrichmentCache()
        val deezer = artProvider()
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(deezer)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )
        val req = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching twice: the first is live, the second is an all-cache-hit
        val first = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))
        val second = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - both calls report the same unverified fuzzy-name provenance; disabled identity
        // resolution outranks the cache-hit reason on both the live and the cached call
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, first.identity.status)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, second.identity.status)
        val firstSuccess = first.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        val secondSuccess = second.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, firstSuccess.provenance)
        assertEquals(LookupProvenance.FUZZY_NAME, secondSuccess.provenance)
    }

    @Test fun `an all-cache-hit call whose types were written under a uniform RESOLVED status still reports NOT_ATTEMPTED_CACHE_HIT`() = runTest {
        // Given - identity resolves, and the type's result is cached under CanonicalStatus.RESOLVED
        val cache = InMemoryEnrichmentCache()
        val deezer = artProvider()
        val id = idProvider(
            EnrichmentResult.Success(
                EnrichmentType.GENRE, com.landofoz.musicmeta.EnrichmentData.Metadata(genres = listOf("rock")), "mb", 1.0f,
            ),
        )
        val e = engine(cache, deezer, id)
        val req = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching twice: the first is live and resolves, the second is an all-cache-hit
        val first = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))
        val second = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - RESOLVED is never replayed from a cache hit: it promises a matchScore the cache
        // envelope does not carry, so the second call reports the honest cache-hit status instead
        assertEquals(CanonicalStatus.RESOLVED, first.identity.status)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, second.identity.status)
    }

    @Test fun `an all-cache-hit call whose types were written under different statuses reports NOT_ATTEMPTED_CACHE_HIT`() = runTest {
        // Given - a cache pre-seeded with two types for the same entity, one written while
        // resolution was disabled and one written because the type needed no resolution at all,
        // read back by an engine with resolution enabled: a disabled engine would report
        // NOT_ATTEMPTED_DISABLED by precedence, which would answer a different question than the
        // one under test here — whether a cache hit replays whatever status its entries carry
        val cache = InMemoryEnrichmentCache()
        val req = EnrichmentRequest.forTrack("Song", "Artist")
        cache.put(
            entityKeyFor(req, EnrichmentType.TRACK_PREVIEW), EnrichmentType.TRACK_PREVIEW,
            EnrichmentResult.Success(
                EnrichmentType.TRACK_PREVIEW,
                com.landofoz.musicmeta.EnrichmentData.TrackPreview(url = "https://x/preview.mp3", source = "deezer", durationMs = 30_000),
                "deezer", 0.8f, provenance = LookupProvenance.FUZZY_NAME,
            ),
            CanonicalStatus.NOT_ATTEMPTED_DISABLED,
        )
        cache.put(
            entityKeyFor(req, EnrichmentType.TRACK_METADATA), EnrichmentType.TRACK_METADATA,
            EnrichmentResult.Success(
                EnrichmentType.TRACK_METADATA,
                com.landofoz.musicmeta.EnrichmentData.TrackMetadata(durationMs = 200_000),
                "deezer", 0.8f, provenance = LookupProvenance.FUZZY_NAME,
            ),
            CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED,
        )
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(emptyList()), cache, EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - enriching both types, entirely from cache
        val result = e.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW, EnrichmentType.TRACK_METADATA))

        // Then - the call reports the honest cache-hit status regardless of what either entry was
        // written under; each result still carries its own stored provenance
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, result.identity.status)
        assertEquals(
            LookupProvenance.FUZZY_NAME,
            (result.raw[EnrichmentType.TRACK_PREVIEW] as EnrichmentResult.Success).provenance,
        )
        assertEquals(
            LookupProvenance.FUZZY_NAME,
            (result.raw[EnrichmentType.TRACK_METADATA] as EnrichmentResult.Success).provenance,
        )
    }

    @Test fun `an engine with identity resolution enabled does not report DISABLED from an entry a disabled engine wrote`() = runTest {
        // Given - one engine with identity resolution disabled writes a cache entry, then a second
        // engine sharing that cache has identity resolution enabled
        val cache = InMemoryEnrichmentCache()
        val preview = artProvider()
        val writer = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(preview)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )
        val req = EnrichmentRequest.forTrack("Song", "Artist")
        writer.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        val identity = idProvider(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb"))
        val reader = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(identity, preview)), cache, EnrichmentConfig(enableIdentityResolution = true),
        )

        // When - the enabled engine serves the same request entirely from the disabled writer's cache
        val replay = reader.enrich(req, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - the call reports the honest cache-hit status, never the disabled reason a past
        // engine configuration wrote and this call's own configuration does not share
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, replay.identity.status)
    }

    @Test fun `an all-cache-hit call reports CACHE when the cache cannot recover the original provenance`() = runTest {
        // Given - a custom cache implementation that never learned to preserve provenance, holding
        // an entry it wrote as a bare `Success(provenance = null)`
        val cache = FakeEnrichmentCache()
        val art = mbidRequiredArtProvider()
        val e = engine(cache, idProvider(EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb")), art)
        val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        cache.stored["${entityKeyFor(req, EnrichmentType.ALBUM_ART)}:${EnrichmentType.ALBUM_ART}"] =
            EnrichmentResult.Success(
                EnrichmentType.ALBUM_ART, com.landofoz.musicmeta.EnrichmentData.Artwork("https://x/art.jpg"), "caa", 0.9f,
            )

        // When - enriching a type that is entirely served from that cache entry
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - the call never reached a provider, and the hit reports CACHE rather than a bare
        // absence a consumer would read as confident
        assertEquals(0, art.enrichCalls.size)
        val success = results.raw[EnrichmentType.ALBUM_ART] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CACHE, success.provenance)
    }

    // --- Null elimination: NOT_ATTEMPTED_NO_PROVIDER is reachable and distinct ---

    @Test fun `no identity provider registered reports NOT_ATTEMPTED_NO_PROVIDER, not a bare absence`() = runTest {
        // Given - a request naming no entity (identifier-only), needing resolution, but no identity
        // provider is registered on this engine at all
        val art = FakeProvider(id = "caa", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = com.landofoz.musicmeta.IdentifierRequirement.MUSICBRAINZ_ID)))
        val e = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(art)), FakeEnrichmentCache(), EnrichmentConfig(enableIdentityResolution = true),
        )
        val req = EnrichmentRequest.forAlbumByMbid("mbid-1")

        // When - enriching
        val results = e.enrich(req, setOf(EnrichmentType.ALBUM_ART))

        // Then - resolution was needed but nothing could attempt it, distinct from disabled or not-required
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_NO_PROVIDER, results.identity.status)
    }
}
