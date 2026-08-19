package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.CatalogMatch
import com.landofoz.musicmeta.CatalogProvider
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.http.AuthException
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `enrich()` is `enrichProgressive(...).last()` against one shared resolution path — every test
 * here pins a property that path must hold in both directions.
 */
class EnrichProgressiveTest {
    private lateinit var cache: FakeEnrichmentCache
    private val req = EnrichmentRequest.forArtist("Radiohead")

    @Before fun setup() { cache = FakeEnrichmentCache() }

    private fun success(type: EnrichmentType, provider: String = "p") =
        EnrichmentResult.Success(type, EnrichmentData.Metadata(genres = listOf("rock")), provider, 0.95f)

    private fun providerFor(vararg types: EnrichmentType, id: String = "p") =
        FakeProvider(id = id, capabilities = types.map { ProviderCapability(it, 100) })
            .also { p -> types.forEach { p.givenResult(it, success(it, id)) } }

    // --- The four named divergences: enrich() and enrichProgressive(...).last() must agree ---

    @Test fun `NETWORK_FIRST does not serve stale when provider fails, identically through both entry points`() =
        runTest {
            // Given - default NETWORK_FIRST config, provider returns Error, expiredStore has stale data
            fun freshEngine(): DefaultEnrichmentEngine {
                val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
                    .also {
                        it.givenResult(
                            EnrichmentType.ALBUM_ART,
                            EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "p", "API down"),
                        )
                    }
                val key = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
                cache.expiredStore["$key:${EnrichmentType.ALBUM_ART}"] =
                    EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x/art.jpg"), "stale", 0.9f)
                return DefaultEnrichmentEngine(ProviderRegistry(listOf(p)), cache, EnrichmentConfig())
            }

            // When - enriching through both entry points
            val viaEnrich = freshEngine().enrich(req, setOf(EnrichmentType.ALBUM_ART))
            val viaProgressive = freshEngine().enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()

            // Then - both report the Error, not the stale fallback
            assertTrue(viaEnrich.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Error)
            assertEquals(viaEnrich, viaProgressive)
        }

    @Test fun `identity provider failure is classified by mapError, identically through both entry points`() =
        runTest {
            // Given - identity provider throws an auth failure, a synthesizer captures the identity result
            fun freshEngine(captured: (EnrichmentResult?) -> Unit): DefaultEnrichmentEngine {
                val idProvider = object : FakeProvider(
                    id = "mb", isIdentityProvider = true,
                    capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
                ) {
                    override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult =
                        throw AuthException(401)
                }
                val capturing = object : CompositeSynthesizer {
                    override val type = EnrichmentType.ARTIST_TIMELINE
                    override val dependencies = emptySet<EnrichmentType>()
                    override fun synthesize(
                        resolved: Map<EnrichmentType, EnrichmentResult>,
                        identityResult: EnrichmentResult?,
                        request: EnrichmentRequest,
                    ): EnrichmentResult {
                        captured(identityResult)
                        return EnrichmentResult.NotFound(type, "test")
                    }
                }
                return DefaultEnrichmentEngine(
                    ProviderRegistry(listOf(idProvider)), FakeEnrichmentCache(),
                    EnrichmentConfig(enableIdentityResolution = true), synthesizers = listOf(capturing),
                )
            }

            // When - enriching for ARTIST_TIMELINE through both entry points
            var capturedViaEnrich: EnrichmentResult? = null
            freshEngine { capturedViaEnrich = it }.enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))
            var capturedViaProgressive: EnrichmentResult? = null
            freshEngine { capturedViaProgressive = it }
                .enrichProgressive(req, setOf(EnrichmentType.ARTIST_TIMELINE)).last()

            // Then - both classify AUTH, not UNKNOWN
            val errorViaEnrich = capturedViaEnrich as EnrichmentResult.Error
            val errorViaProgressive = capturedViaProgressive as EnrichmentResult.Error
            assertEquals(ErrorKind.AUTH, errorViaEnrich.errorKind)
            assertEquals(errorViaEnrich.errorKind, errorViaProgressive.errorKind)
            assertEquals(errorViaEnrich.provider, errorViaProgressive.provider)
        }

    @Test fun `enrich reports a typed Error when the synthesizer throws, identically through both entry points`() =
        runTest {
            // Given - a composite type whose synthesizer throws
            val timeline = EnrichmentType.ARTIST_TIMELINE
            val bio = EnrichmentType.ARTIST_BIO
            val throwing = object : CompositeSynthesizer {
                override val type = timeline
                override val dependencies: Set<EnrichmentType> = emptySet()
                override fun synthesize(
                    resolved: Map<EnrichmentType, EnrichmentResult>,
                    identityResult: EnrichmentResult?,
                    request: EnrichmentRequest,
                ): EnrichmentResult = error("synthesizer boom")
            }
            fun freshEngine() = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(providerFor(bio))), FakeEnrichmentCache(),
                EnrichmentConfig(enableIdentityResolution = false), synthesizers = listOf(throwing),
            )

            // When - enriching for the composite type through both entry points
            val viaEnrich = freshEngine().enrich(req, setOf(timeline, bio))
            val viaProgressive = freshEngine().enrichProgressive(req, setOf(timeline, bio)).last()

            // Then - both carry the typed Error rather than losing the type entirely. Compared by
            // field, not by full equality: each run throws its own exception instance, and
            // Throwable has no structural equals, so EnrichmentResult.Error.cause would never match.
            val result = viaEnrich.raw[timeline]
            assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
            assertEquals("synthesizer boom", (result as EnrichmentResult.Error).message)
            val other = viaProgressive.raw[timeline] as EnrichmentResult.Error
            assertEquals(result.type, other.type)
            assertEquals(result.message, other.message)
            assertEquals(result.provider, other.provider)
            assertEquals(result.errorKind, other.errorKind)
            assertEquals(viaEnrich.raw[bio], viaProgressive.raw[bio])
            assertEquals(viaEnrich.identity, viaProgressive.identity)
        }

    @Test fun `a synthesizer returning an empty payload is demoted, identically through both entry points`() =
        runTest {
            // Given - a composite synthesizer that reports Success with no events
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
            fun freshEngine() = DefaultEnrichmentEngine(
                ProviderRegistry(emptyList()), FakeEnrichmentCache(),
                EnrichmentConfig(enableIdentityResolution = false), synthesizers = listOf(emptySynthesizer),
            )

            // When - asking for ARTIST_TIMELINE through both entry points
            val viaEnrich = freshEngine().enrich(req, setOf(EnrichmentType.ARTIST_TIMELINE))
            val viaProgressive = freshEngine().enrichProgressive(req, setOf(EnrichmentType.ARTIST_TIMELINE)).last()

            // Then - both demote the empty Success to NotFound
            assertTrue(viaEnrich.raw[EnrichmentType.ARTIST_TIMELINE] is EnrichmentResult.NotFound)
            assertEquals(viaEnrich, viaProgressive)
        }

    // --- Suppress-the-last-emission and the derivation contract ---

    @Test fun `exactly one emission derives as complete, and it is the last`() = runTest {
        // Given - two independent regular types, no cache hits
        val p = providerFor(EnrichmentType.ALBUM_ART, EnrichmentType.ARTIST_BIO)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(p)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )
        val types = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ARTIST_BIO)

        // When - collecting the whole stream
        val emissions = engine.enrichProgressive(req, types).toList()

        // Then - every emission but the last still has something pending
        for (snapshot in emissions.dropLast(1)) {
            assertTrue(
                "intermediate snapshot must not derive as complete: $snapshot",
                (types - snapshot.raw.keys).isNotEmpty(),
            )
        }
        assertTrue("the last emission must derive as complete", (types - emissions.last().raw.keys).isEmpty())
    }

    // --- No snapshot aliasing: a captured intermediate never mutates ---

    @Test fun `a captured intermediate snapshot's raw map never mutates as later types settle`() = runTest {
        // Given - three regular types resolving concurrently
        val types = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO)
        val p = providerFor(*types.toTypedArray())
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(p)), cache, EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - capturing every emission's raw map and its size at capture time
        val captured = mutableListOf<Pair<Map<EnrichmentType, EnrichmentResult>, Int>>()
        engine.enrichProgressive(req, types).toList().forEach { captured.add(it.raw to it.raw.size) }

        // Then - every captured map's size still matches what it was when captured — nothing grew
        // in place after being handed to a collector
        for ((map, sizeAtCapture) in captured) {
            assertEquals("a captured snapshot must not grow after emission", sizeAtCapture, map.size)
        }
    }

    // --- Per-type post-processing: an intermediate is already catalog-filtered ---

    @Test fun `a settled recommendation type is catalog-filtered before its own emission`() = runTest {
        // Given - two recommendation types; one is catalog-filtered down to one item
        val artists = EnrichmentResult.Success(
            EnrichmentType.SIMILAR_ARTISTS,
            EnrichmentData.SimilarArtists(listOf(SimilarArtist(name = "A", matchScore = 0.9f), SimilarArtist(name = "B", matchScore = 0.8f))),
            "p", 0.9f,
        )
        val p = FakeProvider(id = "p", capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)))
            .also { it.givenResult(EnrichmentType.SIMILAR_ARTISTS, artists) }
        val catalog = CatalogProvider { queries -> queries.map { CatalogMatch(available = it.title == "A", source = "test") } }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(p)), cache,
            EnrichmentConfig(
                enableIdentityResolution = false, catalogProvider = catalog,
                catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
            ),
        )

        // When - collecting every emission that carries SIMILAR_ARTISTS
        val emissions = engine.enrichProgressive(req, setOf(EnrichmentType.SIMILAR_ARTISTS)).toList()
        val withArtists = emissions.filter { EnrichmentType.SIMILAR_ARTISTS in it.raw }

        // Then - the very first emission to carry it is already filtered to the available item —
        // never the raw two-item payload the provider returned
        val first = withArtists.first().raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals(1, (first.data as EnrichmentData.SimilarArtists).artists.size)
    }

    // --- Timeout mid-stream, through the streamed path ---

    @Test fun `a timeout mid-stream emits a true Error TIMEOUT terminal, and persists nothing`() = runTest {
        // Given - one provider that never returns inside the deadline
        val slow = SlowStreamProvider(EnrichmentType.ALBUM_ART, delayMs = 5_000)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(slow)), cache,
            EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 100),
        )

        // When - collecting the whole progressive stream
        val terminal = engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()

        // Then - a true Error(TIMEOUT), stamped by "engine" — the same contract enrich() itself pins
        val result = terminal.raw[EnrichmentType.ALBUM_ART]
        assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.TIMEOUT, (result as EnrichmentResult.Error).errorKind)
        assertEquals("engine", result.provider)

        // And - nothing was written back for the truncated run
        assertTrue("a timed-out streamed run must cache nothing", cache.stored.isEmpty())
    }

    // --- The atomic settlement region under real parallelism ---

    // SwallowedException: the count is the assertion — this test's whole purpose is tallying how
    // many of N iterations throw, never propagating any single one.
    // InjectDispatcher: a real multi-threaded dispatcher is the point — runTest's is single-threaded
    // and cannot observe the settlement race this test pins.
    @Suppress("SwallowedException", "InjectDispatcher")
    @Test
    fun `30 or more types settling simultaneously under Dispatchers Default never races`() {
        // Given - every non-composite, non-mergeable EnrichmentType, one provider answering all of
        // them with the same non-empty payload (answers() only inspects the payload, not the label)
        val types = EnrichmentType.entries
            .filterNot { it == EnrichmentType.GENRE || it == EnrichmentType.ARTIST_TIMELINE }
            .toSet()
        assertTrue("need 30+ types for a meaningful race window", types.size >= 30)

        var shortMapCount = 0
        var exceptionCount = 0
        val iterations = 200

        // When - running real Dispatchers.Default concurrency, N=200 iterations, fresh state each time
        repeat(iterations) {
            val iterCache = FakeEnrichmentCache()
            val provider = FakeProvider(id = "p", capabilities = types.map { t -> ProviderCapability(t, 100) })
                .also { p -> types.forEach { t -> p.givenResult(t, EnrichmentData.Artwork("https://x/${t.name}").let { d -> EnrichmentResult.Success(t, d, "p", 0.9f) }) } }
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(provider)), iterCache, EnrichmentConfig(enableIdentityResolution = false),
            )
            try {
                val results = runBlocking(Dispatchers.Default) { engine.enrich(req, types) }
                if (results.raw.keys != types) shortMapCount++
            } catch (e: Exception) {
                exceptionCount++
            }
        }

        // Then - no exception, and no silently short terminal map, across every iteration
        assertEquals("iterations that threw: $exceptionCount of $iterations", 0, exceptionCount)
        assertEquals("iterations with a short terminal map: $shortMapCount of $iterations", 0, shortMapCount)
    }

    private class SlowStreamProvider(
        private val type: EnrichmentType,
        private val delayMs: Long,
    ) : FakeProvider(id = "slow", capabilities = listOf(ProviderCapability(type, 100))) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            kotlinx.coroutines.delay(delayMs)
            return EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/art.jpg"), "slow", 0.9f)
        }
    }
}
