package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * A synthetic composite dependency graph three levels deep, used by [NestedCompositeScenarioTest].
 *
 * The graph re-uses real [EnrichmentType] constants that carry no built-in provider chain,
 * [CompositeSynthesizer] or [ResultMerger] of their own. This fixture never calls
 * [EnrichmentEngine.Builder.withDefaultProviders], so the only defaults registered are
 * [DEFAULT_MERGERS] (`GENRE`, `SIMILAR_ARTISTS`, `SIMILAR_TRACKS`, `ARTIST_PHOTO`, `ALBUM_ART`,
 * `ARTIST_TOP_TRACKS`, `ARTIST_POPULARITY`, `TRACK_POPULARITY`) and [DEFAULT_SYNTHESIZERS]
 * (`ARTIST_TIMELINE`, `GENRE_DISCOVERY`). No role below is a key of either, so `TimelineSynthesizer`
 * and `GenreAffinityMatcher` sit in the engine unused rather than interfering.
 *
 * | Role | `EnrichmentType` | Depends on | Provider delay |
 * |---|---|---|---|
 * | `C2` composite | `ARTIST_LOGO` | `C1`, `R_SLOW` | — |
 * | `C1` composite | `CD_ART` | `M_MERGED`, `R_FAST` | — |
 * | `C1B` composite | `ARTIST_BACKGROUND` | `R_VERYFAST` | — |
 * | `R_SLOW` | `LABEL` | — | 300 ms |
 * | `R_FAST` | `RELEASE_DATE` | — | 50 ms |
 * | `R_VERYFAST` | `RELEASE_TYPE` | — | 20 ms |
 * | `M_MERGED` mergeable, 3 providers | `COUNTRY` | — | 40 / 80 / 120 ms |
 * | `U1`..`U4` unrelated | `ALBUM_METADATA`, `ARTIST_BIO`, `LYRICS_SYNCED`, `LYRICS_PLAIN` | — | 200 ms each |
 *
 * Requested types are `{C2, C1B, U1..U4}`. `C1`, `M_MERGED` and the three `R_*` types are reached
 * only as transitive composite dependencies and never requested directly — that is the property
 * under test, and `C1` being a composite reached only that way is the bug this graph pins.
 *
 * Real dispatcher, real wall-clock [delay]s, never `runTest`: virtual time cannot observe the
 * ordering these scenarios turn on. Modeled on `CancelAbWorkloadTest.TimedProvider`.
 */
object NestedCompositeGraph {

    // --- Role constants, named for the table above ---

    val C2: EnrichmentType = EnrichmentType.ARTIST_LOGO
    val C1: EnrichmentType = EnrichmentType.CD_ART
    val C1B: EnrichmentType = EnrichmentType.ARTIST_BACKGROUND
    val R_SLOW: EnrichmentType = EnrichmentType.LABEL
    val R_FAST: EnrichmentType = EnrichmentType.RELEASE_DATE
    val R_VERYFAST: EnrichmentType = EnrichmentType.RELEASE_TYPE
    val M_MERGED: EnrichmentType = EnrichmentType.COUNTRY
    val U1: EnrichmentType = EnrichmentType.ALBUM_METADATA
    val U2: EnrichmentType = EnrichmentType.ARTIST_BIO
    val U3: EnrichmentType = EnrichmentType.LYRICS_SYNCED
    val U4: EnrichmentType = EnrichmentType.LYRICS_PLAIN

    /** The set a caller requests; everything else in the graph is reached transitively. */
    val REQUESTED_TYPES: Set<EnrichmentType> = setOf(C2, C1B, U1, U2, U3, U4)

    /**
     * A [EnrichmentData.Metadata] that answers [type], carrying [trail] in `disambiguation` as the
     * call trail every assertion reads.
     *
     * `disambiguation` alone does not answer every type. `LABEL`, `RELEASE_DATE`, `RELEASE_TYPE`
     * and `COUNTRY` each require their own field (`PayloadAnswers.answersMetadata`), and
     * `demoteUnanswered` turns a `Success` that misses it into `NotFound` before any scheduling
     * design can be observed. Four of this harness's roles are exactly those types, so a payload
     * that ignored this would measure the demotion rather than the schedule under test.
     */
    internal fun answering(type: EnrichmentType, trail: String): EnrichmentData.Metadata = when (type) {
        EnrichmentType.LABEL -> EnrichmentData.Metadata(label = trail, disambiguation = trail)
        EnrichmentType.RELEASE_DATE -> EnrichmentData.Metadata(releaseDate = "1999-01-01", disambiguation = trail)
        EnrichmentType.RELEASE_TYPE -> EnrichmentData.Metadata(releaseType = "album", disambiguation = trail)
        EnrichmentType.COUNTRY -> EnrichmentData.Metadata(country = "GB", disambiguation = trail)
        else -> EnrichmentData.Metadata(disambiguation = trail)
    }

    /** One provider's wall-clock answer delay, real `delay()`, never virtual time. */
    internal class TimedProvider(type: EnrichmentType, private val delayMs: Long, id: String) :
        FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100))) {
        val calls = AtomicInteger(0)
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            calls.incrementAndGet()
            delay(delayMs)
            return EnrichmentResult.Success(type, answering(type, id), id, 0.9f)
        }
    }

    /** Combines `M_MERGED`'s three contributors into one `Success` — contents are a call trail, not a fixture under test. */
    private class JoiningMerger(override val type: EnrichmentType) : ResultMerger {
        override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
            if (results.isEmpty()) return EnrichmentResult.NotFound(type, "no_results")
            val contributors = results.joinToString(",") { it.provider }
            // Same demotion trap as [answering]: the merged payload must answer [type] too, or the
            // merge is invisible downstream no matter which arm scheduled it.
            return EnrichmentResult.Success(type, answering(type, contributors), "merger:${type.name}", 1.0f)
        }
    }

    /**
     * A composite whose `synthesize` just proves every dependency arrived as a `Success` — the
     * scheduling discipline under test, not the payload shape, is what this harness measures.
     */
    private class JoiningSynthesizer(
        override val type: EnrichmentType,
        override val dependencies: Set<EnrichmentType>,
    ) : CompositeSynthesizer {
        override fun synthesize(
            resolved: Map<EnrichmentType, EnrichmentResult>,
            identityResult: EnrichmentResult?,
            request: EnrichmentRequest,
        ): EnrichmentResult {
            val successes = dependencies.mapNotNull { resolved[it] as? EnrichmentResult.Success }
            return if (successes.size == dependencies.size) {
                EnrichmentResult.Success(
                    type,
                    EnrichmentData.Metadata(disambiguation = successes.joinToString(",") { it.provider }),
                    "synth:${type.name}",
                    1.0f,
                )
            } else {
                EnrichmentResult.NotFound(type, "synth:${type.name}")
            }
        }
    }

    /** Every provider and the engine built fresh for one workload run. Rebuilt per run so call counts never carry over. */
    class Workload internal constructor(val engine: EnrichmentEngine, private val providers: List<TimedProvider>) {
        /** Total `enrich()` calls per provider id, read after the run completes. */
        fun providerCallCounts(): Map<String, Int> = providers.associate { it.id to it.calls.get() }
        fun close() = engine.close()
    }

    /** Builds the graph above through the public `Builder.addSynthesizer`/`addProvider`/`addMerger`. */
    fun buildWorkload(): Workload {
        val rSlow = TimedProvider(R_SLOW, 300, "r_slow")
        val rFast = TimedProvider(R_FAST, 50, "r_fast")
        val rVeryFast = TimedProvider(R_VERYFAST, 20, "r_veryfast")
        val m1 = TimedProvider(M_MERGED, 40, "m_merged_1")
        val m2 = TimedProvider(M_MERGED, 80, "m_merged_2")
        val m3 = TimedProvider(M_MERGED, 120, "m_merged_3")
        val u1 = TimedProvider(U1, 200, "u1")
        val u2 = TimedProvider(U2, 200, "u2")
        val u3 = TimedProvider(U3, 200, "u3")
        val u4 = TimedProvider(U4, 200, "u4")
        val providers = listOf(rSlow, rFast, rVeryFast, m1, m2, m3, u1, u2, u3, u4)

        val builder = EnrichmentEngine.Builder()
            .cache(FakeEnrichmentCache())
            .config(EnrichmentConfig(enableIdentityResolution = false))
            .addMerger(JoiningMerger(M_MERGED))
            .addSynthesizer(JoiningSynthesizer(C1, setOf(M_MERGED, R_FAST)))
            .addSynthesizer(JoiningSynthesizer(C1B, setOf(R_VERYFAST)))
            .addSynthesizer(JoiningSynthesizer(C2, setOf(C1, R_SLOW)))
        providers.forEach { builder.addProvider(it) }

        return Workload(builder.build(), providers)
    }

    /** One request against [buildWorkload]'s graph. */
    fun request(): EnrichmentRequest = EnrichmentRequest.forArtist("Nested Composite Probe")
}
