package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * [LrcLibTrackScope]'s own memoization contract, isolated from the provider and HTTP fake stubs
 * `LrcLibProviderTest` uses for the acceptance and sharing behaviour.
 */
class LrcLibTrackScopeTest {

    private val httpClient = FakeHttpClient()

    @Test
    fun `resolve performs one exact-match lookup for repeated calls with the same key`() = runTest {
        // Given - an exact match response and a scope shared across repeated calls
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val scope = LrcLibTrackScope(LrcLibApi(httpClient, RateLimiter(intervalMs = 0)))
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When - resolving the same request twice
        scope.resolve(request)
        scope.resolve(request)

        // Then - only one HTTP call was made
        assertEquals(1, httpClient.requestedUrls.size)
    }

    @Test
    fun `a transient lookup error is not memoized as a miss`() = runTest {
        // Given - the first exact-match call fails transiently; the underlying data would be
        // found on a retry
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val flaky = FlakyOnceHttpClient(httpClient)
        val scope = LrcLibTrackScope(LrcLibApi(flaky, RateLimiter(intervalMs = 0)))
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When - the first lookup throws, then the same key is resolved again in the same scope
        try {
            scope.resolve(request)
            fail("expected the transient failure to propagate")
        } catch (_: IOException) {
            // expected
        }
        val outcome = scope.resolve(request)

        // Then - the retry performs a fresh lookup and finds the track, proving the failed
        // attempt left nothing memoized
        assertTrue(outcome is LrcLibOutcome.Found)
    }

    @Test
    fun `a cancelled lookup is not memoized as a miss`() = runTest {
        // Given - the first exact-match call is cancelled mid-flight; the underlying data would
        // be found on a retry
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val cancelling = CancellingOnceHttpClient(httpClient)
        val scope = LrcLibTrackScope(LrcLibApi(cancelling, RateLimiter(intervalMs = 0)))
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When - the first lookup is cancelled, on a job separate from this test's own
        val cancelled = CoroutineScope(Job()).async { scope.resolve(request) }
        try {
            cancelled.await()
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            // expected
        }
        val outcome = scope.resolve(request)

        // Then - the retry performs a fresh lookup rather than reading a memoized miss
        assertTrue(outcome is LrcLibOutcome.Found)
    }

    /** Throws [IOException] on its first `fetchJsonResult` call, then delegates normally. */
    private class FlakyOnceHttpClient(private val delegate: HttpClient) : HttpClient by delegate {
        private var thrown = false

        override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
            if (!thrown) {
                thrown = true
                throw IOException("simulated transient failure")
            }
            return delegate.fetchJsonResult(url)
        }
    }

    /** Cancels the caller and throws on its first `fetchJsonResult` call, then delegates normally. */
    private class CancellingOnceHttpClient(private val delegate: HttpClient) : HttpClient by delegate {
        private var cancelled = false

        override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
            if (!cancelled) {
                cancelled = true
                currentCoroutineContext()[Job]?.cancel()
                throw CancellationException("caller went away")
            }
            return delegate.fetchJsonResult(url)
        }
    }

    private companion object {
        val SYNCED_LYRICS_JSON = """
            {
                "id": 123,
                "trackName": "Creep",
                "artistName": "Radiohead",
                "albumName": "Pablo Honey",
                "duration": 238.0,
                "instrumental": false,
                "syncedLyrics": "[00:00.00] When you were here before",
                "plainLyrics": "When you were here before"
            }
        """.trimIndent()
    }
}
