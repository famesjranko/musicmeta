package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * What nested composite resolution has to get right, one scenario per defect.
 *
 * S1 and S2 pin the transitive graph: a composite dependency that is itself a composite is
 * synthesized, however many levels deep. S3 pins that a dependency is resolved through the path its
 * own registration implies — merged if a [ResultMerger] is registered for it — rather than through
 * whichever path the caller's request happened to select. S4 and S5 pin the two ways the graph can
 * be malformed: a cycle, and a [CompositeSynthesizer.dependencies] that does not answer the same
 * way twice.
 */
// InjectDispatcher: a real dispatcher, not runTest virtual time, is the point — these scenarios
// turn on the order real concurrent settlements land in, which virtual time cannot observe.
@Suppress("InjectDispatcher")
class NestedCompositeScenarioTest {

    // --- S1: a composite dependency of a composite is never synthesized ---

    @Test fun `S1 - outer composite depending on an inner composite invokes the inner synthesizer`() =
        runBlocking(Dispatchers.Default) {
            // Given - the harness graph, where C2 depends on the composite C1 (and on R_SLOW)
            val workload = NestedCompositeGraph.buildWorkload()
            try {
                // When - only the outer composite C2 is requested
                val result = workload.engine.enrich(NestedCompositeGraph.request(), setOf(NestedCompositeGraph.C2))

                // Then - the inner composite C1 was synthesized (its providers were called) and the
                // outer composite settled as a Success built from it
                val innerCalls = workload.providerCallCounts()["m_merged_1"] ?: 0
                assertTrue("expected C1's own dependency provider to have been called", innerCalls > 0)
                assertTrue(
                    "expected C2 to settle Success, was ${result.result(NestedCompositeGraph.C2)}",
                    result.result(NestedCompositeGraph.C2) is EnrichmentResult.Success,
                )
            } finally {
                workload.close()
            }
        }

    // --- S2: the board is one level deep ---

    @Test fun `S2 - a three-level composite graph settles the outermost type`() =
        runBlocking(Dispatchers.Default) {
            // Given - the same three-level graph as S1: C2 depends on C1, C1 depends on M_MERGED and R_FAST
            val workload = NestedCompositeGraph.buildWorkload()
            try {
                // When - the outer composite C2 is requested
                val result = workload.engine.enrich(NestedCompositeGraph.request(), setOf(NestedCompositeGraph.C2))

                // Then - C2 settles Success, not the NotFound this defect leaves it with today
                assertEquals(EnrichmentResult.Success::class.java, result.result(NestedCompositeGraph.C2)?.javaClass)
            } finally {
                workload.close()
            }
        }

    // --- S3: a mergeable dependency is merged only if the caller happened to request it ---

    private class GenreProvider(id: String, private val tag: GenreTag, priority: Int) :
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, priority))) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.Success(type, EnrichmentData.Metadata(genreTags = listOf(tag)), id, 0.9f)
    }

    private fun buildGenreEngine(): EnrichmentEngine =
        EnrichmentEngine.Builder()
            .cache(FakeEnrichmentCache())
            .config(EnrichmentConfig(enableIdentityResolution = false))
            .addProvider(GenreProvider("genre_rock", GenreTag("rock", 0.9f, sources = listOf("genre_rock")), priority = 100))
            .addProvider(GenreProvider("genre_jazz", GenreTag("jazz", 0.9f, sources = listOf("genre_jazz")), priority = 50))
            .addProvider(GenreProvider("genre_soul", GenreTag("soul", 0.9f, sources = listOf("genre_soul")), priority = 25))
            .build()

    @Test fun `S3 - requesting GENRE_DISCOVERY alone must merge GENRE the same as requesting both`() =
        runBlocking(Dispatchers.Default) {
            // Given - three genre providers (rock, jazz, soul) behind a stack whose GENRE has a registered merger
            val discoveryOnlyEngine = buildGenreEngine()
            val bothEngine = buildGenreEngine()
            val request = EnrichmentRequest.forArtist("Genre Probe")
            try {
                // When - GENRE_DISCOVERY is requested alone, and separately alongside GENRE
                val discoveryOnly = discoveryOnlyEngine.enrich(request, setOf(EnrichmentType.GENRE_DISCOVERY))
                val both = bothEngine.enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.GENRE_DISCOVERY))

                // Then - GENRE was merged (not single-winner) when both were requested: its genreTags
                // carry both providers' contributions, not just the higher-priority one
                val genreWhenBoth = both.result(EnrichmentType.GENRE) as? EnrichmentResult.Success
                val tagsWhenBoth = (genreWhenBoth?.data as? EnrichmentData.Metadata)?.genreTags.orEmpty()
                assertEquals(
                    "expected the GenreMerger to have merged all three providers' tags",
                    setOf("rock", "jazz", "soul"),
                    tagsWhenBoth.map { it.name }.toSet(),
                )
                // And - the same request must produce the same GENRE_DISCOVERY answer whichever way
                // GENRE was reached: today it does not, because GENRE_DISCOVERY-alone resolves GENRE
                // through the single-winner chain instead of the registered merger
                assertEquals(
                    "GENRE_DISCOVERY must not depend on whether the caller also requested GENRE",
                    both.result(EnrichmentType.GENRE_DISCOVERY),
                    discoveryOnly.result(EnrichmentType.GENRE_DISCOVERY),
                )
            } finally {
                discoveryOnlyEngine.close()
                bothEngine.close()
            }
        }

    // --- S4: a dependency cycle is refused at build() ---

    private class NoOpSynthesizer(override val type: EnrichmentType, override val dependencies: Set<EnrichmentType>) :
        CompositeSynthesizer {
        override fun synthesize(
            resolved: Map<EnrichmentType, EnrichmentResult>,
            identityResult: EnrichmentResult?,
            request: EnrichmentRequest,
        ): EnrichmentResult = EnrichmentResult.NotFound(type, "unused")
    }

    @Test fun `S4 - a two-type dependency cycle is refused at build with both types named`() {
        // Given - A depends on B and B depends on A
        val builder = EnrichmentEngine.Builder()
            .addSynthesizer(NoOpSynthesizer(EnrichmentType.CREDITS, setOf(EnrichmentType.ARTIST_LINKS)))
            .addSynthesizer(NoOpSynthesizer(EnrichmentType.ARTIST_LINKS, setOf(EnrichmentType.CREDITS)))

        // When - build() is called
        val thrown = assertThrows(IllegalArgumentException::class.java) { builder.build() }

        // Then - the message names both cycle members
        assertTrue("expected CREDITS in the message, was: ${thrown.message}", thrown.message?.contains("CREDITS") == true)
        assertTrue("expected ARTIST_LINKS in the message, was: ${thrown.message}", thrown.message?.contains("ARTIST_LINKS") == true)
    }

    @Test fun `S4 - a self-dependency is refused at build with the type named`() {
        // Given - A depends on itself
        val builder = EnrichmentEngine.Builder()
            .addSynthesizer(NoOpSynthesizer(EnrichmentType.ALBUM_TRACKS, setOf(EnrichmentType.ALBUM_TRACKS)))

        // When - build() is called
        val thrown = assertThrows(IllegalArgumentException::class.java) { builder.build() }

        // Then - the message names the self-dependent type
        assertTrue("expected ALBUM_TRACKS in the message, was: ${thrown.message}", thrown.message?.contains("ALBUM_TRACKS") == true)
    }

    // --- S5: the dependency graph is read once, so the board and the scheduler cannot disagree ---

    /** Answers a different dependency set on every read — the contract violation S5 is about. */
    private class DriftingSynthesizer(
        override val type: EnrichmentType,
        private val answers: List<Set<EnrichmentType>>,
    ) : CompositeSynthesizer {
        private val reads = AtomicInteger(0)
        val readCount: Int get() = reads.get()
        override val dependencies: Set<EnrichmentType>
            get() = answers[minOf(reads.getAndIncrement(), answers.size - 1)]

        override fun synthesize(
            resolved: Map<EnrichmentType, EnrichmentResult>,
            identityResult: EnrichmentResult?,
            request: EnrichmentRequest,
        ): EnrichmentResult = EnrichmentResult.Success(
            type,
            EnrichmentData.Metadata(disambiguation = resolved.keys.joinToString(",") { it.name }),
            "synth:${type.name}",
            1.0f,
        )
    }

    @Test fun `S5 - a synthesizer whose dependencies drift cannot desync the board from the scheduler`() =
        runBlocking(Dispatchers.Default) {
            // Given - a synthesizer that names LABEL on its first read and RELEASE_DATE on every later one
            val drifting = DriftingSynthesizer(
                EnrichmentType.ARTIST_LOGO,
                listOf(setOf(EnrichmentType.LABEL), setOf(EnrichmentType.RELEASE_DATE)),
            )
            val engine = EnrichmentEngine.Builder()
                .cache(FakeEnrichmentCache())
                .config(EnrichmentConfig(enrichTimeoutMs = 5_000))
                .addProvider(NestedCompositeGraph.TimedProvider(EnrichmentType.LABEL, 10, "label"))
                .addProvider(NestedCompositeGraph.TimedProvider(EnrichmentType.RELEASE_DATE, 10, "release_date"))
                .addSynthesizer(drifting)
                .build()

            // When - the composite is enriched
            val result = try {
                engine.enrich(NestedCompositeGraph.request(), setOf(EnrichmentType.ARTIST_LOGO))
            } finally {
                engine.close()
            }

            // Then - it settles Success rather than the ENGINE_CLOSED error a desynced board produces
            val settled = result.result(EnrichmentType.ARTIST_LOGO)
            assertTrue(
                "expected Success, was $settled - a board built from one read of dependencies and " +
                    "a scheduler working from another awaits a key the board does not carry",
                settled is EnrichmentResult.Success,
            )

            // Then - the graph was read once, at construction, and never again
            assertEquals(1, drifting.readCount)
        }

    // --- S6: one type cannot be both a composite and a mergeable ---

    @Test fun `S6 - a type with both a synthesizer and a merger is refused at build`() {
        // Given - a merger and a synthesizer registered for the same type
        val builder = EnrichmentEngine.Builder()
            .addMerger(NoOpMerger(EnrichmentType.ARTIST_LOGO))
            .addSynthesizer(NoOpSynthesizer(EnrichmentType.ARTIST_LOGO, setOf(EnrichmentType.LABEL)))

        // When - build() is called
        val thrown = assertThrows(IllegalArgumentException::class.java) { builder.build() }

        // Then - the message names the type carrying both registrations
        assertTrue(
            "expected ARTIST_LOGO in the message, was: ${thrown.message}",
            thrown.message?.contains("ARTIST_LOGO") == true,
        )
    }

    @Test fun `S6 - a synthesizer for a type with a built-in merger is refused too`() {
        // Given - a synthesizer for GENRE, which DEFAULT_MERGERS already registers GenreMerger for
        val builder = EnrichmentEngine.Builder()
            .addSynthesizer(NoOpSynthesizer(EnrichmentType.GENRE, setOf(EnrichmentType.LABEL)))

        // When - build() is called
        val thrown = assertThrows(IllegalArgumentException::class.java) { builder.build() }

        // Then - a default registration counts the same as a caller's own
        assertTrue(
            "expected GENRE in the message, was: ${thrown.message}",
            thrown.message?.contains("GENRE") == true,
        )
    }

    /** A merger that is never expected to run — S6 is about the registration, not the merge. */
    private class NoOpMerger(override val type: EnrichmentType) : ResultMerger {
        override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult =
            EnrichmentResult.NotFound(type, "unreachable")
    }
}
