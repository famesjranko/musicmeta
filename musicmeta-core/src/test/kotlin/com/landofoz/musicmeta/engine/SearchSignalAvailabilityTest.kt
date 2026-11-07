package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SearchSignalAvailabilityTest {
    private val http = FakeHttpClient()

    @Test
    fun `unavailable signal provider sends no search request`() = runTest {
        // Given - the signal source is registered but unavailable.
        val provider = FakeProvider(id = "unavailable", isAvailable = false)
        val policy = SearchArtistHints(ProviderRegistry(listOf(provider)), EnrichmentLogger.NoOp)
        http.givenJsonResponse("signal", """{"results":[]}""")

        // When - the policy is asked to load an artist signal from that source.
        val found = policy.artists(EnrichmentRequest.forTrack("Song", ""), provider) { _, _ ->
            http.fetchJsonResult("https://example.com/signal")
            listOf("Artist")
        }

        // Then - the unavailable source receives no request and contributes no hint.
        assertTrue(found.isEmpty())
        assertTrue(http.requestedUrls.isEmpty())
    }

    @Test
    fun `open signal breaker prevents the real iTunes request without adding a skip log`() = runTest {
        // Given - the registered iTunes provider has an open breaker.
        val itunes = ITunesProvider(http, RateLimiter(0))
        val breaker = CircuitBreaker(failureThreshold = 1)
        breaker.recordFailure()
        val registry = ProviderRegistry(listOf(itunes), breakerFor = { breaker })
        val warnings = mutableListOf<String>()
        val logger = object : EnrichmentLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
        }
        val policy = SearchArtistHints(registry, logger)
        http.givenJsonResponse("itunes.apple.com", """{"results":[{"trackName":"Song","artistName":"Artist"}]}""")

        // When - title-only discovery requests an artist hint.
        val found = policy.artists(EnrichmentRequest.forTrack("Song", ""))

        // Then - the breaker blocks the request and the existing silent skip behaviour remains.
        assertTrue(found.isEmpty())
        assertTrue(http.requestedUrls.isEmpty())
        assertTrue(warnings.isEmpty())
    }
}
