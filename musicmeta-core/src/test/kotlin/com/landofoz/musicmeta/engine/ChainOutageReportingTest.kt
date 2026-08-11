package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chain that produced nothing because it never asked anyone owes the consumer an `Error`, not the
 * `NotFound` that reads as "this entity has no such data". The boundary is who spoke: one provider
 * that ran answers for the chain, however many others were skipped.
 */
class ChainOutageReportingTest {
    private val req = EnrichmentRequest.forArtist("Radiohead")

    private fun open() = CircuitBreaker(failureThreshold = 1).also { it.recordFailure() }

    @Test fun `resolve reports an outage when every eligible provider is breaker-open`() = runTest {
        // Given - the type's only provider is tripped, as ListenBrainz's 500s leave it
        val p1 = FakeProvider(id = "p1")
        val chain = ProviderChain(EnrichmentType.ARTIST_RADIO_DISCOVERY, listOf(p1), mapOf("p1" to open()))

        // When - resolving while the breaker is open
        val result = chain.resolve(req)

        // Then - an outage, not a clean absence, and nobody was called
        assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals(0, p1.enrichCalls.size)
    }

    @Test fun `resolveAll reports an outage when every eligible provider is breaker-open`() = runTest {
        // Given - both merge sources for a mergeable type are tripped
        val chain = ProviderChain(
            EnrichmentType.GENRE,
            listOf(FakeProvider(id = "p1"), FakeProvider(id = "p2")),
            mapOf("p1" to open(), "p2" to open()),
        )

        // When - collecting every provider's result
        val results = chain.resolveAll(req)

        // Then - the merger's caller can tell "everyone was skipped" from "everyone had nothing"
        assertTrue("expected an outage, got ${results.failure}", results.failure is EnrichmentResult.Error)
    }

    @Test fun `resolveAll keeps the failure when every provider errored`() = runTest {
        // Given - two healthy providers that both error, so no Success survives to be merged
        fun failing(id: String) = FakeProvider(id = id).also {
            it.givenResult(EnrichmentType.GENRE, EnrichmentResult.Error(EnrichmentType.GENRE, id, "500"))
        }
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(failing("p1"), failing("p2")))

        // When - collecting every provider's result
        val results = chain.resolveAll(req)

        // Then - the failure survives the empty success list, as it does in resolve
        assertTrue("expected a failure, got ${results.failure}", results.failure is EnrichmentResult.Error)
    }

    @Test fun `resolve reports a genuine absence as NotFound`() = runTest {
        // Given - a healthy provider that simply has no data
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(FakeProvider(id = "p1")))

        // When - resolving
        val result = chain.resolve(req)

        // Then - still a clean absence
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test fun `resolve reports a genuine absence when one provider answered alongside a tripped one`() = runTest {
        // Given - p1 holds the data but is tripped, p2 is healthy and has nothing
        val p1 = FakeProvider(id = "p1").also {
            it.givenResult(
                EnrichmentType.GENRE,
                EnrichmentResult.Success(
                    EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "p1", 0.9f,
                ),
            )
        }
        val chain = ProviderChain(
            EnrichmentType.GENRE,
            listOf(p1, FakeProvider(id = "p2")),
            mapOf("p1" to open(), "p2" to CircuitBreaker()),
        )

        // When - resolving
        val result = chain.resolve(req)

        // Then - a provider that ran answers for the chain, whatever the skipped one held
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test fun `resolve reports a genuine absence when the skipped provider lacked an identifier`() = runTest {
        // Given - p1 needs an MBID the request has not got, p2 is healthy and has nothing
        val p1 = FakeProvider(
            id = "p1",
            capabilities = listOf(
                ProviderCapability(
                    EnrichmentType.GENRE, 100,
                    identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
                ),
            ),
        )
        val chain = ProviderChain(EnrichmentType.GENRE, listOf(p1, FakeProvider(id = "p2")))

        // When - resolving without an MBID
        val result = chain.resolve(req)

        // Then - an unmet identifier is not an outage; only an open breaker is
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test fun `enrich reports the outage once the only provider for a type has tripped`() = runTest {
        // Given - one provider for the type, failing the way ListenBrainz's 500s do
        val type = EnrichmentType.ARTIST_RADIO_DISCOVERY
        val lb = FakeProvider(id = "listenbrainz", capabilities = listOf(ProviderCapability(type, 100)))
            .also {
                it.givenResult(type, EnrichmentResult.Error(type, "listenbrainz", "500", errorKind = ErrorKind.NETWORK))
            }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(lb)), FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When - the same engine is called past the breaker's failure threshold
        val outcomes = (1..6).map { engine.enrich(req, setOf(type)).raw[type] }

        // Then - the call the breaker short-circuits still reads as an outage, not an absence
        assertEquals(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD, lb.enrichCalls.size)
        assertTrue("expected Error, got ${outcomes[5]}", outcomes[5] is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (outcomes[5] as EnrichmentResult.Error).errorKind)
    }

    @Test fun `enrich reports an absence when a mergeable type's one success was too weak to keep`() = runTest {
        // Given - one provider answers below minConfidence and the other errors
        val type = EnrichmentType.GENRE
        val weak = FakeProvider(id = "weak", capabilities = listOf(ProviderCapability(type, 100))).also {
            it.givenResult(
                type,
                EnrichmentResult.Success(type, EnrichmentData.Metadata(genres = listOf("rock")), "weak", 0.1f),
            )
        }
        val broken = FakeProvider(id = "broken", capabilities = listOf(ProviderCapability(type, 90)))
            .also { it.givenResult(type, EnrichmentResult.Error(type, "broken", "500", errorKind = ErrorKind.NETWORK)) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(weak, broken)), FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false), mergers = listOf(GenreMerger),
        )

        // When - enriching, so the confidence gate drops the one success before the merge
        val result = engine.enrich(req, setOf(type)).raw[type]

        // Then - a provider answered, so its rejected answer stands and the failure does not
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test fun `enrich reports the outage for a mergeable type whose providers all error`() = runTest {
        // Given - both merge sources for GENRE failing, before any breaker has opened
        val type = EnrichmentType.GENRE
        fun failing(id: String) = FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100)))
            .also { it.givenResult(type, EnrichmentResult.Error(type, id, "500", errorKind = ErrorKind.NETWORK)) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(failing("lastfm"), failing("deezer"))), FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false), mergers = listOf(GenreMerger),
        )

        // When - enriching once
        val result = engine.enrich(req, setOf(type)).raw[type]

        // Then - the merger's empty input does not read as a clean absence
        assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
    }
}
