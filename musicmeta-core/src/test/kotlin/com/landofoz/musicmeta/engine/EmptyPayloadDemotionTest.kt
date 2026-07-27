package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `Success` must answer the type it claims to answer. Splits into the per-type enumeration
 * ([answers]) and the engine gate that applies it.
 */
class EmptyPayloadDemotionTest {

    // --- The reported case, end to end through the real provider ---

    /**
     * "Paranoid Android" by Radiohead: MusicBrainz matches the recording at score 100 and the
     * recording carries no tags, so the mapper produces an all-null Metadata. Before the gate this
     * surfaced as GENRE Success at confidence 1.0 with nothing in it.
     */
    @Test
    fun `tagless recording at a perfect identity score is NotFound for GENRE`() = runTest {
        // Given — a perfect-score recording match with no tags at all
        val http = FakeHttpClient()
        http.givenJsonResponse("recording?query", TAGLESS_RECORDING_SCORE_100)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When — asking for the one type that empty payload claimed to answer
        val results = engine.enrich(
            EnrichmentRequest.forTrack("Paranoid Android", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )

        // Then — NotFound, not a 100%-confident Success carrying nothing
        assertTrue(
            "expected NotFound, got ${results.raw[EnrichmentType.GENRE]}",
            results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound,
        )
    }

    @Test
    fun `a tagged recording is still a GENRE Success`() = runTest {
        // Given — the same shape, but the recording carries a tag
        val http = FakeHttpClient()
        http.givenJsonResponse("recording?query", TAGGED_RECORDING_SCORE_100)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When
        val results = engine.enrich(
            EnrichmentRequest.forTrack("Paranoid Android", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )

        // Then — the gate demotes empty payloads only
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(listOf("alternative rock"), (success.data as EnrichmentData.Metadata).genres)
    }

    // --- The engine gate, per affected type ---

    private fun engineWith(vararg results: EnrichmentResult): DefaultEnrichmentEngine {
        val provider = FakeProvider(
            id = "p",
            capabilities = results.map { ProviderCapability(it.typeOf(), 100) },
        ).also { p -> results.forEach { p.givenResult(it.typeOf(), it) } }
        return DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )
    }

    private fun EnrichmentResult.typeOf(): EnrichmentType = when (this) {
        is EnrichmentResult.Success -> type
        is EnrichmentResult.NotFound -> type
        is EnrichmentResult.RateLimited -> type
        is EnrichmentResult.Error -> type
    }

    private fun success(type: EnrichmentType, data: EnrichmentData) =
        EnrichmentResult.Success(type, data, "p", 1.0f)

    /**
     * The whole point of the per-type enumeration: one all-null-but-one Metadata answers exactly the
     * type whose field it filled, and no other. LABEL is filled here, so LABEL is the only Success.
     */
    @Test
    fun `a Metadata answers only the type whose field it filled`() = runTest {
        // Given — a payload that answers LABEL and nothing else, offered for four types
        val onlyLabel = EnrichmentData.Metadata(label = "Parlophone")
        val types = listOf(
            EnrichmentType.LABEL, EnrichmentType.GENRE,
            EnrichmentType.RELEASE_DATE, EnrichmentType.COUNTRY,
        )
        val engine = engineWith(*types.map { success(it, onlyLabel) }.toTypedArray())

        // When
        val results = engine.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), types.toSet())

        // Then
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.Success)
        for (type in types - EnrichmentType.LABEL) {
            assertTrue("$type should be demoted", results.raw[type] is EnrichmentResult.NotFound)
        }
    }

    @Test
    fun `an empty list payload is demoted`() = runTest {
        // Given — a provider claiming success with no members
        val engine = engineWith(
            success(EnrichmentType.BAND_MEMBERS, EnrichmentData.BandMembers(emptyList())),
        )

        // When
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.BAND_MEMBERS),
        )

        // Then
        assertTrue(results.raw[EnrichmentType.BAND_MEMBERS] is EnrichmentResult.NotFound)
    }

    @Test
    fun `identity fan-out does not spread a payload to types it cannot answer`() = runTest {
        // Given — identity resolves with a payload that only carries a country
        val identity = EnrichmentResult.Success(
            EnrichmentType.COUNTRY, EnrichmentData.Metadata(country = "GB"), "mb", 1.0f,
            resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
        )
        val provider = FakeProvider(
            id = "mb", isIdentityProvider = true,
            capabilities = listOf(
                ProviderCapability(EnrichmentType.COUNTRY, 100),
                ProviderCapability(EnrichmentType.LABEL, 100),
            ),
        ).also { it.givenIdentityResult(identity) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — asking for the type it answers and one it does not
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.COUNTRY, EnrichmentType.LABEL),
        )

        // Then — COUNTRY inherited from identity, LABEL not handed the same empty answer
        assertTrue(results.raw[EnrichmentType.COUNTRY] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.NotFound)
    }

    /**
     * A cached empty `Success` is a miss, not a `NotFound`. Demoting it in place would pin the entry
     * for its whole TTL — 90 days for `GENRE` — re-demoted on every call and never refetched.
     */
    @Test
    fun `a cached empty Success is refetched and healed, not just demoted`() = runTest {
        // Given — an empty GENRE Success in the cache, as an older build would have written, and a
        // provider that has a real answer
        val cache = FakeEnrichmentCache()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val key = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.GENRE)
        cache.put(
            key, EnrichmentType.GENRE,
            EnrichmentResult.Success(EnrichmentType.GENRE, EnrichmentData.Metadata(), "stale-empty", 1.0f),
        )
        val provider = FakeProvider(
            id = "p",
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.GENRE,
                EnrichmentResult.Success(
                    EnrichmentType.GENRE,
                    EnrichmentData.Metadata(genreTags = listOf(GenreTag("alternative rock", 0.9f))),
                    "p", 0.9f,
                ),
            )
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When — the empty entry is still well within its TTL
        val results = engine.enrich(request, setOf(EnrichmentType.GENRE))

        // Then — the provider was consulted rather than the empty entry being served or demoted...
        assertEquals(1, provider.enrichCalls.size)
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(listOf("alternative rock"), (success.data as EnrichmentData.Metadata).genres)

        // ...and the cache entry healed, so the next call is a hit on real data
        val rewritten = cache.get(key, EnrichmentType.GENRE)
        assertTrue(
            "cached entry should have been overwritten, still: ${rewritten?.data}",
            rewritten != null && rewritten.data.answers(EnrichmentType.GENRE),
        )
    }

    @Test
    fun `an empty stale entry does not replace the Error that would tell a consumer to retry`() = runTest {
        // Given — STALE_IF_ERROR, a failing provider, and an expired empty entry to fall back on.
        // LABEL, not GENRE: a mergeable type turns a provider Error into the merger's NotFound,
        // which never reaches the stale fallback at all.
        val cache = FakeEnrichmentCache()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val key = DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.LABEL)
        cache.expiredStore["$key:${EnrichmentType.LABEL}"] =
            EnrichmentResult.Success(EnrichmentType.LABEL, EnrichmentData.Metadata(), "stale-empty", 1.0f)
        val provider = FakeProvider(
            id = "failing",
            capabilities = listOf(ProviderCapability(EnrichmentType.LABEL, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.LABEL,
                EnrichmentResult.Error(EnrichmentType.LABEL, "failing", "API down"),
            )
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR),
        )

        // When
        val results = engine.enrich(request, setOf(EnrichmentType.LABEL))

        // Then — the Error survives; a stale entry answering nothing is worse than being told to retry
        assertTrue(
            "expected the Error to survive, got ${results.raw[EnrichmentType.LABEL]}",
            results.raw[EnrichmentType.LABEL] is EnrichmentResult.Error,
        )
    }

    @Test
    fun `a synthesizer returning an empty payload is demoted`() = runTest {
        // Given — a composite synthesizer that reports Success with no events, as TimelineSynthesizer
        // does for an artist with no dates anywhere
        val emptySynthesizer = object : CompositeSynthesizer {
            override val type = EnrichmentType.ARTIST_TIMELINE
            override val dependencies = emptySet<EnrichmentType>()
            override fun synthesize(
                resolved: Map<EnrichmentType, EnrichmentResult>,
                identityResult: EnrichmentResult?,
                request: EnrichmentRequest,
            ) = EnrichmentResult.Success(
                type, EnrichmentData.ArtistTimeline(emptyList()), "timeline_synthesizer", 0.95f,
            )
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(emptyList()),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
            synthesizers = listOf(emptySynthesizer),
        )

        // When
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.ARTIST_TIMELINE),
        )

        // Then — CompositeSynthesizer is a public extension point; its output is gated too
        assertTrue(results.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.NotFound)
    }

    @Test
    fun `a merger returning an empty payload is demoted`() = runTest {
        // Given — a provider with a real genre, and a merger that loses it. ResultMerger is a public
        // extension point too, and a built-in can merge to nothing (GenreMerger returns its first
        // input as-is when there are no tags to merge).
        val emptyMerger = object : ResultMerger {
            override val type = EnrichmentType.GENRE
            override fun merge(results: List<EnrichmentResult.Success>) =
                EnrichmentResult.Success(type, EnrichmentData.Metadata(), "empty_merger", 1.0f)
        }
        val provider = FakeProvider(
            id = "p",
            capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.GENRE,
                EnrichmentResult.Success(
                    EnrichmentType.GENRE,
                    EnrichmentData.Metadata(genreTags = listOf(GenreTag("rock", 0.9f))),
                    "p", 0.9f,
                ),
            )
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
            mergers = listOf(emptyMerger),
        )

        // When — the merger runs on a non-empty input, so this gates the *output*, not the input
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )

        // Then
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound)
    }

    // --- The enumeration itself ---

    @Test
    fun `answers enumerates which Metadata field answers which type`() {
        assertTrue(EnrichmentData.Metadata(genres = listOf("rock")).answers(EnrichmentType.GENRE))
        assertTrue(
            EnrichmentData.Metadata(genreTags = listOf(GenreTag("rock", 0.9f)))
                .answers(EnrichmentType.GENRE),
        )
        assertTrue(EnrichmentData.Metadata(label = "XL").answers(EnrichmentType.LABEL))
        assertTrue(EnrichmentData.Metadata(releaseDate = "1997").answers(EnrichmentType.RELEASE_DATE))
        assertTrue(EnrichmentData.Metadata(releaseType = "Album").answers(EnrichmentType.RELEASE_TYPE))
        assertTrue(EnrichmentData.Metadata(country = "GB").answers(EnrichmentType.COUNTRY))
        // ALBUM_METADATA is the grab bag: any field will do, all-null will not
        assertTrue(EnrichmentData.Metadata(barcode = "123").answers(EnrichmentType.ALBUM_METADATA))
        assertFalse(EnrichmentData.Metadata().answers(EnrichmentType.ALBUM_METADATA))
        // An empty list is as unanswered as a null one
        assertFalse(EnrichmentData.Metadata(genres = emptyList()).answers(EnrichmentType.GENRE))
    }

    @Test
    fun `answers rejects blank strings in payloads that cannot be null`() {
        assertFalse(EnrichmentData.Artwork(url = " ").answers(EnrichmentType.ALBUM_ART))
        assertTrue(EnrichmentData.Artwork(url = "https://x/a.jpg").answers(EnrichmentType.ALBUM_ART))
        assertFalse(EnrichmentData.Biography(text = "", source = "wp").answers(EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `an instrumental track answers a lyrics request`() {
        assertTrue(EnrichmentData.Lyrics(isInstrumental = true).answers(EnrichmentType.LYRICS_PLAIN))
        assertFalse(EnrichmentData.Lyrics().answers(EnrichmentType.LYRICS_PLAIN))
    }

    private companion object {
        val TAGLESS_RECORDING_SCORE_100 = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 100,
                "title": "Paranoid Android"
              }]
            }
        """.trimIndent()

        val TAGGED_RECORDING_SCORE_100 = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 100,
                "title": "Paranoid Android",
                "tags": [{"name": "alternative rock", "count": 3}]
              }]
            }
        """.trimIndent()
    }
}
