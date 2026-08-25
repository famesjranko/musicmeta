package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fan-out that dies of an internal fault must not tell the consumer the engine was closed.
 *
 * [ErrorKind.ENGINE_CLOSED]'s own KDoc and its `CHANGELOG` line both promise the narrow meaning —
 * "the engine was `close()`d before this type settled". `runProgressiveFanOut`'s `finally` is
 * reached by every path that stops a run short of its terminal snapshot, `close()` and any uncaught
 * throw alike, so before this the promise was false and an internal defect was reported as a closed
 * engine with no trace of the real cause.
 */
// InjectDispatcher: a real dispatcher, not runTest virtual time — the failure races real settlement.
@Suppress("InjectDispatcher")
class FanOutFailureStampTest {

    /**
     * Throws a `Throwable` that is not an `Exception`, which is what reaches the fan-out's own
     * `finally` — `StrategyGuard` and `ProviderChain` both `catch (e: Exception)`, so an `Error`
     * passes through every guard the engine has. `NoClassDefFoundError` rather than a contrived
     * one: a consumer whose build omits an optional transitive dependency gets exactly this from
     * the first provider call that touches the missing class.
     */
    private class ExplodingProvider(type: EnrichmentType) :
        FakeProvider(id = "exploding", capabilities = listOf(ProviderCapability(type, 100))) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            throw NoClassDefFoundError("com/example/MissingOptionalDependency")
    }

    private class QuietProvider(type: EnrichmentType) :
        FakeProvider(id = "quiet", capabilities = listOf(ProviderCapability(type, 100))) {
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.Success(type, EnrichmentData.Biography("text", "quiet"), "quiet", 0.9f)
    }

    @Test fun `an uncaught fan-out fault is not stamped ENGINE_CLOSED`() = runBlocking(Dispatchers.Default) {
        // Given - an engine whose provider for one requested type throws a non-cancellation fault
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(ExplodingProvider(EnrichmentType.LABEL), QuietProvider(EnrichmentType.ARTIST_BIO))),
            FakeEnrichmentCache(),
            EnrichmentConfig(enrichTimeoutMs = 5_000),
        )

        // When - the two types are enriched and the fault escapes the fan-out
        val results = try {
            runCatching {
                engine.enrich(
                    EnrichmentRequest.forArtist("Fan Out Failure"),
                    setOf(EnrichmentType.LABEL, EnrichmentType.ARTIST_BIO),
                )
            }
        } finally {
            engine.close()
        }

        // Then - the engine was never closed, so nothing may claim it was
        val settled = results.getOrNull()
        assertNotNull("expected a terminal result rather than a propagated Error", settled)
        val closed = settled!!.raw.values
            .filterIsInstance<EnrichmentResult.Error>()
            .filter { error -> error.errorKind == ErrorKind.ENGINE_CLOSED }
        assertTrue("a fault that is not close() must not be stamped ENGINE_CLOSED, was $closed", closed.isEmpty())

        // Then - the real cause reaches the consumer instead of being replaced by a closed-engine story
        val bio = settled.result(EnrichmentType.ARTIST_BIO)
        assertTrue(
            "expected the surviving type to carry the fault, was $bio",
            bio !is EnrichmentResult.Error || bio.message.contains("MissingOptionalDependency"),
        )
    }

    @Test fun `closing the engine still stamps ENGINE_CLOSED`() = runBlocking(Dispatchers.Default) {
        // Given - an engine closed before a slow type can settle
        val slow = object : FakeProvider(
            id = "slow",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, 100)),
        ) {
            override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
                delay(10_000)
                return EnrichmentResult.NotFound(type, "unreachable")
            }
        }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(slow)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enrichTimeoutMs = 30_000),
        )

        // When - the engine is closed while the fan-out is still in flight
        val results = coroutineScope {
            val call = async {
                engine.enrich(EnrichmentRequest.forArtist("Closed Mid Flight"), setOf(EnrichmentType.ARTIST_BIO))
            }
            delay(100)
            engine.close()
            runCatching { call.await() }
        }

        // Then - the close path keeps its own kind, so this test can tell the two apart
        val settled = results.getOrNull()
        assertNotNull("expected a terminal result from the closed run", settled)
        val bio = settled!!.result(EnrichmentType.ARTIST_BIO)
        assertTrue("expected an Error for the unsettled type, was $bio", bio is EnrichmentResult.Error)
        assertEquals(ErrorKind.ENGINE_CLOSED, (bio as EnrichmentResult.Error).errorKind)
    }
}
