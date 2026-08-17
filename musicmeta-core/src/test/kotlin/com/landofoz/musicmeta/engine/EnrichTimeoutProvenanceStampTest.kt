package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrich-timeout-drops-provenance regression, isolated from MusicBrainz: a non-MusicBrainz
 * identity provider's `Success` (`provenance == null`, as any provider that never sets its own may
 * legally return) is write-through-copied into `results` by `resolveIdentity`, and a deadline that
 * expires before `stampProvenance` runs at the end of the timed block must still stamp it in the
 * `if (!completed)` fallback — the fix `EnrichTimeoutProvenanceRegressionTest` also pins.
 *
 * **Why a second pin for the same fix.** `EnrichTimeoutProvenanceRegressionTest` drives this through
 * MusicBrainz, whose `enrichArtist` name-search branch was separately hardened to set its own
 * provenance directly. That hardening independently satisfies MusicBrainz's own scenario, so it can
 * no longer tell the engine's `stampProvenance` fallback apart from its absence: reverting the
 * fallback alone leaves that pin green. A fake identity provider has no such hardening, so only the
 * fallback can save it.
 */
class EnrichTimeoutProvenanceStampTest {

    private val req = EnrichmentRequest.forArtist("Test Artist")

    /** Answers immediately with a Success write-through-eligible for COUNTRY, provenance unset. */
    private fun identityProvider() = FakeProvider(id = "fake-identity", isIdentityProvider = true).also {
        it.givenIdentityResult(
            EnrichmentResult.Success(
                type = EnrichmentType.GENRE,
                data = EnrichmentData.Metadata(country = "GB"),
                provider = "fake-identity",
                confidence = 1.0f,
                resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "fake-mbid"),
            ),
        )
    }

    /** Outlives [delayMs], so a deadline below it truncates the run while this call is in flight. */
    private fun slowProvider(delayMs: Long) = object : FakeProvider(
        id = "fake-slow",
        capabilities = listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100)),
    ) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            delay(delayMs)
            val data = EnrichmentData.SimilarArtists(listOf(SimilarArtist(name = "Other Artist", matchScore = 0.9f)))
            return EnrichmentResult.Success(type, data, id, 0.9f)
        }
    }

    private fun engine(enrichTimeoutMs: Long, delayMs: Long) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(identityProvider(), slowProvider(delayMs))),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = true, enrichTimeoutMs = enrichTimeoutMs),
    )

    private val types = setOf(EnrichmentType.COUNTRY, EnrichmentType.SIMILAR_ARTISTS)

    @Test
    fun `identity write-through Success from a non-MusicBrainz provider keeps its provenance even when the run times out`() =
        runTest {
            // Given - an identity provider that resolves COUNTRY by write-through with no provenance
            // of its own, and a second, slow provider whose chain is still in flight at the deadline
            val engine = engine(enrichTimeoutMs = 100, delayMs = 5_000)

            // When - enriching for the write-through type and the type whose chain outlives the deadline
            val results = engine.enrich(req, types)

            // Then - SIMILAR_ARTISTS timing out is the proof the run actually truncated, and COUNTRY
            // is still owed a provenance by EnrichmentResult.kt's promise regardless of that truncation
            val similarArtists = results.raw[EnrichmentType.SIMILAR_ARTISTS]
            assertTrue(
                "expected SIMILAR_ARTISTS to time out, which is what proves this run truncated; got $similarArtists",
                similarArtists is EnrichmentResult.Error && similarArtists.errorKind == ErrorKind.TIMEOUT,
            )
            val country = results.raw[EnrichmentType.COUNTRY]
            assertTrue("expected a Success for COUNTRY, got $country", country is EnrichmentResult.Success)
            assertNotNull(
                "EnrichmentResult.kt promises provenance is null only for a result built outside the " +
                    "engine — this one is engine-produced",
                (country as EnrichmentResult.Success).provenance,
            )
        }

    @Test
    fun `control - the same fixtures and request complete and keep provenance at a generous timeout`() = runTest {
        // Given - the identical fixtures and request as the test above, differing only in
        // enrichTimeoutMs and a delay short enough for both calls to land inside it
        val engine = engine(enrichTimeoutMs = 5_000, delayMs = 100)

        // When - enriching for the same two types with room for both calls to complete
        val results = engine.enrich(req, types)

        // Then - both calls complete, so SIMILAR_ARTISTS is a genuine Success rather than a timeout,
        // and COUNTRY keeps its provenance — proving the same fixtures and request work cleanly
        // when nothing truncates, so a timeout run's own truncation is what the other test exercises
        val similarArtists = results.raw[EnrichmentType.SIMILAR_ARTISTS]
        assertTrue(
            "expected SIMILAR_ARTISTS to complete at a generous timeout, got $similarArtists",
            similarArtists is EnrichmentResult.Success,
        )
        val country = results.raw[EnrichmentType.COUNTRY]
        assertTrue("expected a Success for COUNTRY, got $country", country is EnrichmentResult.Success)
        assertNotNull(
            "control run: provenance must be non-null here, or the test above proves nothing",
            (country as EnrichmentResult.Success).provenance,
        )
    }
}
