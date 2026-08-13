package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChainExecution] is the fact a cache write-back needs and a `NotFound`/`Success` alone cannot
 * carry: whether the chain that produced it ran every eligible provider, skipped one for a missing
 * identifier, or skipped one for an open breaker — for both [ProviderChain.resolveWithExecution]
 * (first-wins) and [ProviderChain.resolveAllWithExecution] (collect-all).
 */
class ProviderChainExecutionTest {
    private val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
    private fun art(p: String) = EnrichmentResult.Success(EnrichmentType.ALBUM_ART, EnrichmentData.Artwork("https://x.com/$p.jpg"), p, 0.95f)

    private fun open() = CircuitBreaker(failureThreshold = 1).also { it.recordFailure() }

    // --- resolveWithExecution (regular chain) ---

    @Test fun `resolveWithExecution reports a fully exhausted chain`() = runTest {
        // Given - two NONE-requirement providers, both eligible and both answering NotFound
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "p1")) }
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "p2")) }

        // When - resolving with execution
        val (result, execution) = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolveWithExecution(req)

        // Then - both ran, nobody was skipped, and the chain is not identifier-incomplete
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(listOf("p1", "p2"), execution.attemptedProviderIds)
        assertTrue(execution.skippedForMissingIdentifier.isEmpty())
        assertTrue(execution.skippedForOpenBreaker.isEmpty())
        assertFalse(execution.identifierIncomplete)
    }

    @Test fun `resolveWithExecution reports an identifier-incomplete chain`() = runTest {
        // Given - one NONE provider answering NotFound, one MBID-only provider the request lacks
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "p1")) }
        val p2 = FakeProvider(
            id = "p2",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ALBUM_ART, 50, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
            ),
        )

        // When - resolving with execution against a request carrying no MBID
        val (result, execution) = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolveWithExecution(req)

        // Then - p1 answered, p2 is a missing-identifier skip with its requirement recorded
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(listOf("p1"), execution.attemptedProviderIds)
        assertEquals(mapOf("p2" to IdentifierRequirement.MUSICBRAINZ_ID), execution.skippedForMissingIdentifier)
        assertTrue(execution.identifierIncomplete)
        assertEquals(0, p2.enrichCalls.size)
    }

    @Test fun `resolveWithExecution reports a breaker-open skip separately from a missing identifier`() = runTest {
        // Given - a tripped breaker for p1, and p2 skipped for a missing identifier
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
        val p2 = FakeProvider(
            id = "p2",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ALBUM_ART, 50, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
            ),
        )
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2), mapOf("p1" to open(), "p2" to CircuitBreaker()))

        // When - resolving with execution
        val (result, execution) = chain.resolveWithExecution(req)

        // Then - the chain never asked anyone: an outage Error, not a clean NotFound
        assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
        assertTrue(execution.attemptedProviderIds.isEmpty())
        assertEquals(listOf("p1"), execution.skippedForOpenBreaker)
        assertEquals(mapOf("p2" to IdentifierRequirement.MUSICBRAINZ_ID), execution.skippedForMissingIdentifier)
        assertTrue(execution.identifierIncomplete)
    }

    @Test fun `resolveWithExecution stops the ledger at the first Success`() = runTest {
        // Given - p1 succeeds, p2 would answer too but is lower priority
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p1")) }
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 50)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, art("p2")) }

        // When - resolving with execution
        val (result, execution) = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2)).resolveWithExecution(req)

        // Then - only p1 is in the ledger; p2 was never reached
        assertEquals("p1", (result as EnrichmentResult.Success).provider)
        assertEquals(listOf("p1"), execution.attemptedProviderIds)
        assertEquals(0, p2.enrichCalls.size)
    }

    // --- resolveAllWithExecution (mergeable chain) ---

    @Test fun `resolveAllWithExecution reports a fully exhausted chain`() = runTest {
        // Given - two NONE-requirement providers, both eligible and both answering NotFound
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.NotFound(EnrichmentType.GENRE, "p1")) }
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 50)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.NotFound(EnrichmentType.GENRE, "p2")) }

        // When - resolving all with execution
        val (chainResults, execution) = ProviderChain(EnrichmentType.GENRE, listOf(p1, p2)).resolveAllWithExecution(req)

        // Then - both ran, no successes, and the chain is not identifier-incomplete
        assertTrue(chainResults.successes.isEmpty())
        assertEquals(setOf("p1", "p2"), execution.attemptedProviderIds.toSet())
        assertFalse(execution.identifierIncomplete)
        assertTrue(execution.skippedForOpenBreaker.isEmpty())
    }

    @Test fun `resolveAllWithExecution reports an identifier-incomplete chain`() = runTest {
        // Given - one NONE provider answering NotFound, one release-group-only provider the
        // request lacks
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.NotFound(EnrichmentType.GENRE, "p1")) }
        val p2 = FakeProvider(
            id = "p2",
            capabilities = listOf(
                ProviderCapability(
                    EnrichmentType.GENRE, 50,
                    identifierRequirement = IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID,
                ),
            ),
        )

        // When - resolving all with execution against a request carrying no release-group id
        val (chainResults, execution) = ProviderChain(EnrichmentType.GENRE, listOf(p1, p2)).resolveAllWithExecution(req)

        // Then - p1 ran, p2 is a missing-identifier skip
        assertTrue(chainResults.successes.isEmpty())
        assertEquals(listOf("p1"), execution.attemptedProviderIds)
        assertEquals(
            mapOf("p2" to IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID),
            execution.skippedForMissingIdentifier,
        )
        assertTrue(execution.identifierIncomplete)
        assertEquals(0, p2.enrichCalls.size)
    }

    @Test fun `resolveAllWithExecution reports a breaker-open skip`() = runTest {
        // Given - p1's breaker is open, p2 is eligible and answers NotFound
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)))
        val p2 = FakeProvider(id = "p2", capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 50)))
            .also { it.givenResult(EnrichmentType.GENRE, EnrichmentResult.NotFound(EnrichmentType.GENRE, "p2")) }
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(p1, p2), mapOf("p1" to open(), "p2" to CircuitBreaker()))

        // When - resolving all with execution
        val (chainResults, execution) = chain.resolveAllWithExecution(req)

        // Then - p2 ran, p1 is a breaker-open skip, and the request had every identifier it needed
        assertTrue(chainResults.successes.isEmpty())
        assertEquals(listOf("p2"), execution.attemptedProviderIds)
        assertEquals(listOf("p1"), execution.skippedForOpenBreaker)
        assertFalse(execution.identifierIncomplete)
        assertEquals(0, p1.enrichCalls.size)
    }

    @Test fun `an available provider missing its identifier is absent from the eligible ledger, not the breaker one`() = runTest {
        // Given - a provider that requires an identifier the request never supplies
        val p1 = FakeProvider(
            id = "p1",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ALBUM_ART, 100, identifierRequirement = IdentifierRequirement.WIKIDATA_ID),
            ),
        )

        // When - resolving with execution
        val (result, execution) = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1)).resolveWithExecution(req)

        // Then - a clean NotFound ("all_providers"), not the breaker outage Error: nothing tripped
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("all_providers", (result as EnrichmentResult.NotFound).provider)
        assertEquals(mapOf("p1" to IdentifierRequirement.WIKIDATA_ID), execution.skippedForMissingIdentifier)
        assertTrue(execution.skippedForOpenBreaker.isEmpty())
    }

    @Test fun `resolve and resolveAll discard the execution ledger their WithExecution siblings expose`() = runTest {
        // Given - the same identifier-incomplete setup as above
        val p1 = FakeProvider(id = "p1", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)))
            .also { it.givenResult(EnrichmentType.ALBUM_ART, EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "p1")) }
        val p2 = FakeProvider(
            id = "p2",
            capabilities = listOf(
                ProviderCapability(EnrichmentType.ALBUM_ART, 50, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID),
            ),
        )
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(p1, p2))

        // When - resolving through the plain entry points
        val resolveResult = chain.resolve(req)
        val resolveAllResult = ProviderChain(EnrichmentType.GENRE, listOf()).resolveAll(req)

        // Then - the same result the WithExecution variant produces, with no ledger to inspect
        val (expected, _) = chain.resolveWithExecution(req)
        assertEquals(expected, resolveResult)
        assertTrue(resolveAllResult.successes.isEmpty())
    }
}
