package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `failedIdentityResolution` (`ProgressiveFanOut.kt`) is every site that never learned an identity
 * resolution outcome — a run abandoned before its own started, or one whose deadline fired before
 * identity resolution ran. Same precedence as the live path and the cache-hit fast path: a config
 * with `enableIdentityResolution = false` outranks any of those reasons, since nothing was ever
 * going to attempt identity resolution regardless of why the fan-out itself never got there. See
 * `docs/pitfalls.md`, "Traps in the pipeline".
 */
// InjectDispatcher: DelayingProvider's delay must be real suspension for the timeout/close()
// races below to be genuine, so each timed section runs on a real clock inside withContext —
// runTest still bounds the test itself and keeps every other delay virtual.
@Suppress("InjectDispatcher")
class FailedIdentityResolutionDisabledTest {

    private val req = EnrichmentRequest.forArtist("Radiohead")

    /** One type, answered after [delayMs] of suspension — real, on the caller's own dispatcher. */
    private class DelayingProvider(
        type: EnrichmentType,
        private val delayMs: Long,
    ) : FakeProvider(id = "slow", capabilities = listOf(ProviderCapability(type, 100))) {
        override suspend fun enrich(
            request: EnrichmentRequest,
            type: EnrichmentType,
        ): com.landofoz.musicmeta.EnrichmentResult {
            delay(delayMs)
            return com.landofoz.musicmeta.EnrichmentResult.Success(
                type,
                com.landofoz.musicmeta.EnrichmentData.Artwork("https://x/${type.name}"),
                "slow",
                0.9f,
            )
        }
    }

    @Test
    fun `a call arriving after close() on a disabled engine reports NOT_ATTEMPTED_DISABLED, not FAILED`() = runTest {
        // Given - identity resolution disabled, and an engine closed before this request/types key
        // was ever registered, so the abandoned snapshot is built with no fan-out ever having run
        val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 5_000)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )
        engine.close()

        // When - a call for that never-seen key is made after close()
        val result = withContext(Dispatchers.Default) {
            withTimeout(8_000) {
                engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()
            }
        }

        // Then - the config, not the abandonment, decides why identity was never resolved
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, result.identity.status)
    }

    @Test
    fun `a run whose deadline fires before identity resolution starts reports NOT_ATTEMPTED_DISABLED on a disabled engine`() = runTest {
        // Given - identity resolution disabled and a deadline of 0ms, so the fan-out's own timeout
        // fires before resolveUncachedTypes ever assigns session.identityResolution
        val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 5_000)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 0),
        )

        // When - enriching under a deadline that expires before the fan-out's body can run
        val result = withContext(Dispatchers.Default) {
            withTimeout(8_000) {
                engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()
            }
        }

        // Then - the config, not the timeout, decides why identity was never resolved
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_DISABLED, result.identity.status)
    }
}
