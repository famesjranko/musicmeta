package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.BudgetedTransientRetry
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.asAttempt
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeHttpClient]'s own coverage. A fake that lies is worse than no fake: every provider test in
 * this module reads its answers as the upstream's, so a channel that silently repeats when it was
 * asked to sequence turns a retry test green without a retry ever happening.
 */
class FakeHttpClientTest {

    private val client = FakeHttpClient()

    @Test
    fun `sequenced statuses are consumed in order`() = runTest {
        // Given - a URL stubbed with a rate limit followed by a success
        client.givenHttpResultsInTurn(
            "search",
            HttpResult.RateLimited(retryAfterMs = 1000L),
            HttpResult.Ok(JSONObject("""{"ok":true}""")),
        )

        // When - fetching that URL twice
        val first = client.fetchJsonResult("https://example.test/search?q=a")
        val second = client.fetchJsonResult("https://example.test/search?q=a")

        // Then - the answers arrive in the order they were given
        assertEquals(HttpResult.RateLimited(1000L), first)
        assertTrue("second call should be the success, was $second", second is HttpResult.Ok)
    }

    @Test
    fun `the last sequenced status repeats once the list is spent`() = runTest {
        // Given - a two-entry sequence whose last entry is a 500
        client.givenHttpResultsInTurn(
            "search",
            HttpResult.RateLimited(),
            HttpResult.ServerError(500, "boom"),
        )

        // When - fetching four times, two calls past the end of the list
        val results = (1..4).map { client.fetchJsonResult("https://example.test/search") }

        // Then - the list is not exhausted into UNSTUBBED; the last entry stands for every call after it
        assertEquals(HttpResult.RateLimited(), results[0])
        assertEquals(HttpResult.ServerError(500, "boom"), results[1])
        assertEquals(HttpResult.ServerError(500, "boom"), results[2])
        assertEquals(HttpResult.ServerError(500, "boom"), results[3])
    }

    @Test
    fun `a single givenHttpResult repeats forever`() = runTest {
        // Given - one status stubbed the way the existing consumer files stub it
        client.givenHttpResult("search", HttpResult.RateLimited(retryAfterMs = 500L))

        // When - fetching three times
        val results = (1..3).map { client.fetchJsonResult("https://example.test/search") }

        // Then - every call sees it, which is the behaviour the sequenced channel must not change
        assertEquals(listOf<HttpResult<JSONObject>>(
            HttpResult.RateLimited(500L), HttpResult.RateLimited(500L), HttpResult.RateLimited(500L),
        ), results)
    }

    @Test
    fun `a sequenced status is preferred over a single one for the same URL`() = runTest {
        // Given - the same URL carrying both a sequence and a single status
        client.givenHttpResult("search", HttpResult.ServerError(503))
        client.givenHttpResultsInTurn("search", HttpResult.RateLimited(), HttpResult.ClientError(404))

        // When - fetching three times
        val results = (1..3).map { client.fetchJsonResult("https://example.test/search") }

        // Then - the sequence wins throughout, including after it is spent
        assertEquals(HttpResult.RateLimited(), results[0])
        assertEquals(HttpResult.ClientError(404), results[1])
        assertEquals(HttpResult.ClientError(404), results[2])
    }

    @Test
    fun `an unstubbed URL is still a 404 rather than a transient`() = runTest {
        // Given - a fake with nothing configured

        // When - fetching an address it has never heard of
        val result = client.fetchJsonResult("https://example.test/nothing")

        // Then - a ClientError 404, so a test that simply omits a stub asserts the empty-result path
        assertEquals(HttpResult.ClientError(404, "No response configured"), result)
    }

    @Test
    fun `givenError still means NetworkError`() = runTest {
        // Given - a URL stubbed as a dropped connection
        client.givenError("search")

        // When - fetching it
        val result = client.fetchJsonResult("https://example.test/search")

        // Then - a NetworkError, unchanged: three consumer files read this as the transport failure
        assertTrue("expected NetworkError, was $result", result is HttpResult.NetworkError)
    }

    @Test
    fun `givenError still wins over a sequenced status`() = runTest {
        // Given - a URL carrying both a dropped connection and a sequence
        client.givenError("search")
        client.givenHttpResultsInTurn("search", HttpResult.Ok(JSONObject("{}")))

        // When - fetching it
        val result = client.fetchJsonResult("https://example.test/search")

        // Then - givenError is still checked first, so its meaning did not move
        assertTrue("expected NetworkError, was $result", result is HttpResult.NetworkError)
    }

    @Test
    fun `a sequence set on the JSON path leaves the array path alone`() = runTest {
        // Given - a sequence on the object channel only
        client.givenHttpResultsInTurn("search", HttpResult.RateLimited())

        // When - fetching the same URL as an array
        val result = client.fetchJsonArrayResult("https://example.test/search")

        // Then - the array channel is untouched and answers unstubbed
        assertEquals(HttpResult.ClientError(404, "No response configured"), result)
    }

    @Test
    fun `a provider behind a retrying client recovers from 429 then 200`() = runTest {
        // Given - Deezer's track search shedding once and then answering, behind the shared ladder
        client.givenHttpResultsInTurn(
            "search/track",
            HttpResult.RateLimited(retryAfterMs = 1000L),
            HttpResult.Ok(JSONObject(TRACK_SEARCH_WITH_PREVIEW_RESPONSE)),
        )
        val provider = DeezerProvider(RetryingClient(client), RateLimiter(0))

        // When - enriching for a track preview
        val result = provider.enrich(
            EnrichmentRequest.forTrack("Karma Police", "Radiohead"),
            EnrichmentType.TRACK_PREVIEW,
        )

        // Then - the ladder asked twice and the provider returned the success, not the rate limit
        assertEquals(2, client.requestedUrls.count { it.contains("search/track") })
        assertTrue("expected Success, was $result", result is EnrichmentResult.Success)
        assertEquals(
            "https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3",
            ((result as EnrichmentResult.Success).data as EnrichmentData.TrackPreview).url,
        )
    }

    /**
     * A [FakeHttpClient] with the shipped retry ladder in front of it, which is where retry lives —
     * both real clients compose [BudgetedTransientRetry] and a provider has none of its own. Private
     * to this file on purpose: if another child needs a retrying fake it goes in the kit, rather than
     * being copied here and adapted.
     */
    private class RetryingClient(private val delegate: FakeHttpClient) : HttpClient {
        private val retry = BudgetedTransientRetry(attemptTimeoutMs = 0)

        override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
            fetchJsonResult(url, emptyMap())

        override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> =
            retry.execute { delegate.fetchJsonResult(url, headers).asAttempt() }

        override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> =
            retry.execute { delegate.fetchJsonArrayResult(url).asAttempt() }

        override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> =
            retry.execute { delegate.fetchRedirectUrlResult(url).asAttempt() }

        override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
            retry.execute { delegate.postJsonResult(url, body).asAttempt() }

        override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
            retry.execute { delegate.postJsonArrayResult(url, body).asAttempt() }
    }

    companion object {
        private val TRACK_SEARCH_WITH_PREVIEW_RESPONSE = """
            {"data":[{
                "id":789,
                "title":"Karma Police",
                "artist":{"name":"Radiohead"},
                "album":{"title":"OK Computer"},
                "preview":"https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3",
                "duration":30
            }]}
        """.trimIndent()
    }
}
