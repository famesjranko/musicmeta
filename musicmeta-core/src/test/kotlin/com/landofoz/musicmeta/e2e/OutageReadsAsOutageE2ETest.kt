package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.ApiKey
import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.CircuitBreaker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A real upstream outage keeps reporting itself once the breaker short-circuits the provider.
 *
 * The unit suite fakes the failure; this asks a live API that is genuinely refusing. The call the
 * breaker skips is the one that used to read as a clean absence, and no fake can prove the real
 * providers, HTTP client and retry ladder classify the refusal as a failure in the first place —
 * only a provider whose upstream is actually down can.
 *
 * Self-skipping by design: it verifies behaviour *during* an outage, so a healthy upstream leaves it
 * nothing to check. That keeps it honest when the endpoint comes back rather than turning recovery
 * into a red test. [EnrichmentType.ARTIST_RADIO_DISCOVERY] is the case to reach for because
 * ListenBrainz is its only provider — a second source would answer and mask the skip.
 */
class OutageReadsAsOutageE2ETest {

    @Test
    fun `a live outage still reports an Error on the call the breaker skips`() = runBlocking {
        // Given - e2e enabled, a token, and an upstream that is refusing right now
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")
        val token = E2ETestFixture.prop("listenbrainz.token")
        assumeTrue("needs listenbrainz.token", token.isNotBlank())
        val type = EnrichmentType.ARTIST_RADIO_DISCOVERY
        val engine = EnrichmentEngine.Builder()
            .config(EnrichmentConfig(enableIdentityResolution = false))
            .apiKeys(ApiKeyConfig.of(ApiKey.LISTENBRAINZ_USER_TOKEN to token))
            .withDefaultProviders()
            .build()
        val request = EnrichmentRequest.forArtist("Radiohead")
        val first = engine.enrich(request, setOf(type)).raw[type]
        assumeTrue("LB Radio is answering — no outage to observe, got $first", first is EnrichmentResult.Error)

        // When - the same engine is called past the breaker's threshold, so the last call is skipped
        val outcomes = listOf(first) +
            (2..CircuitBreaker.DEFAULT_FAILURE_THRESHOLD + 1).map { engine.enrich(request, setOf(type)).raw[type] }

        // Then - the skipped call reports the outage rather than the absence it once claimed
        val last = outcomes.last()
        assertTrue("expected Error on the skipped call, got $last", last is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (last as EnrichmentResult.Error).errorKind)
        println("live outage: ${outcomes.size} calls, last = $last")
    }
}
