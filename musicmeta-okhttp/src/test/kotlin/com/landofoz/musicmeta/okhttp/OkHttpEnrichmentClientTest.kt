package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.http.HttpResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class OkHttpEnrichmentClientTest {

    private val server = MockWebServer()
    private val okHttpClient = OkHttpClient()
    private val client = OkHttpEnrichmentClient(okHttpClient, "TestAgent/1.0")

    @After fun tearDown() { server.shutdown() }

    private fun url(path: String = "/test"): String = server.url(path).toString()

    // ---- fetchRedirectUrlResult ----

    @Test fun `fetchRedirectUrlResult returns Ok with Location header for 307 response`() = runTest {
        // Given - server responds 307 with a Location header
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", "https://example.com/redirected")
        )

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is Ok wrapping the Location header value
        assertTrue(result is HttpResult.Ok)
        assertEquals("https://example.com/redirected", (result as HttpResult.Ok).body)
        assertEquals(307, result.statusCode)
    }

    @Test fun `fetchRedirectUrlResult returns Ok with original URL for 200 response`() = runTest {
        // Given - server responds 200 with no redirect
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val requestUrl = url()

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(requestUrl)

        // Then - result is Ok wrapping the original request URL
        assertEquals(requestUrl, (result as HttpResult.Ok).body)
    }

    @Test fun `fetchRedirectUrlResult returns ClientError for 404 response`() = runTest {
        // Given - no artwork at that address, a genuine empty result
        server.enqueue(MockResponse().setResponseCode(404))

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is ClientError carrying the 404 status
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `fetchRedirectUrlResult returns RateLimited for 429 response`() = runTest {
        // Given - server responds 429 with a Retry-After header
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - a status, not the indistinguishable failure a nullable return collapsed it to
        assertEquals(5000L, (result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `fetchRedirectUrlResult returns ServerError for 500 response`() = runTest {
        // Given - server responds 500
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - result is ServerError carrying the 500 status
        assertEquals(500, (result as HttpResult.ServerError).statusCode)
    }

    @Test fun `fetchRedirectUrlResult returns ClientError for a 3xx with no Location header`() = runTest {
        // Given - server responds 302 with no Location header
        server.enqueue(MockResponse().setResponseCode(302))

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - a redirect with nowhere to go is not a usable URL
        assertEquals(302, (result as HttpResult.ClientError).statusCode)
    }

    // ---- User-Agent header ----

    @Test fun `User-Agent header is sent on every request`() = runTest {
        // Given - server queued to respond 200
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        // When - a JSON request is made
        client.fetchJsonResult(url())

        // Then - the captured request carries the configured User-Agent
        val request = server.takeRequest()
        assertEquals("TestAgent/1.0", request.getHeader("User-Agent"))
    }

    // ---- Gzip decompression ----

    @Test fun `fetchJsonResult decompresses gzip-encoded response transparently`() = runTest {
        // Given - compress a JSON body with GZIP
        val jsonBody = """{"artist":"The Beatles"}"""
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { gzip -> gzip.write(jsonBody.toByteArray()) }
        }.toByteArray()
        val buffer = Buffer().write(compressed)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Encoding", "gzip")
                .setBody(buffer)
        )

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - OkHttp decompresses transparently; JSON is parsed correctly
        assertEquals("The Beatles", (result as HttpResult.Ok).body.getString("artist"))
    }

    // ---- fetchJsonResult ----

    @Test fun `fetchJsonResult returns HttpResult Ok for 200 response`() = runTest {
        // Given - server responds 200 with a JSON body
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"album":"Let It Be"}"""))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - result is Ok wrapping the parsed JSON body
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals("Let It Be", ok.body.getString("album"))
        assertEquals(200, ok.statusCode)
    }

    @Test fun `fetchJsonResult returns HttpResult RateLimited with retryAfterMs for 429 with Retry-After header`() = runTest {
        // Given - server responds 429 with a Retry-After header
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "5")
                .setBody("rate limited")
        )

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - result is RateLimited with retryAfterMs converted from the header
        assertTrue(result is HttpResult.RateLimited)
        assertEquals(5000L, (result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `fetchJsonResult returns HttpResult RateLimited with null retryAfterMs for 429 without Retry-After header`() = runTest {
        // Given - server responds 429 with no Retry-After header
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - result is RateLimited with a null retryAfterMs
        assertTrue(result is HttpResult.RateLimited)
        assertNull((result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `fetchJsonResult returns HttpResult ClientError for 404 response`() = runTest {
        // Given - server responds 404
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - result is ClientError carrying the status and body
        assertTrue(result is HttpResult.ClientError)
        val err = result as HttpResult.ClientError
        assertEquals(404, err.statusCode)
        assertEquals("Not Found", err.body)
    }

    @Test fun `fetchJsonResult returns HttpResult ServerError for 503 response`() = runTest {
        // Given - server responds 503
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - result is ServerError carrying the status and body
        assertTrue(result is HttpResult.ServerError)
        val err = result as HttpResult.ServerError
        assertEquals(503, err.statusCode)
        assertEquals("Service Unavailable", err.body)
    }

    @Test fun `fetchJsonResult returns HttpResult NetworkError for invalid JSON in 200 body`() = runTest {
        // Given - server responds 200 with a non-JSON body
        server.enqueue(MockResponse().setResponseCode(200).setBody("not valid json"))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - result is NetworkError reporting a JSON parse error
        assertTrue(result is HttpResult.NetworkError)
        assertTrue((result as HttpResult.NetworkError).message.contains("JSON parse error"))
    }

    @Test fun `fetchJsonResult returns HttpResult NetworkError when server is unreachable`() = runTest {
        // Given - shut down the server before making a request
        server.shutdown()

        // When - the JSON result is fetched from an unreachable address
        val result = client.fetchJsonResult("http://localhost:1/unreachable")

        // Then - result is NetworkError
        assertTrue(result is HttpResult.NetworkError)
    }

    // ---- fetchJsonArrayResult ----

    @Test fun `fetchJsonArrayResult returns HttpResult Ok with JSONArray for 200 response`() = runTest {
        // Given - server responds 200 with a JSON array of two elements
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":1},{"id":2}]"""))

        // When - the JSON array result is fetched
        val result = client.fetchJsonArrayResult(url())

        // Then - result is Ok wrapping a JSONArray of length two
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals(2, ok.body.length())
    }

    @Test fun `fetchJsonArrayResult returns HttpResult ClientError for 400 response`() = runTest {
        // Given - server responds 400
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        // When - the JSON array result is fetched
        val result = client.fetchJsonArrayResult(url())

        // Then - result is ClientError carrying the 400 status
        assertTrue(result is HttpResult.ClientError)
        assertEquals(400, (result as HttpResult.ClientError).statusCode)
    }

    // ---- postJsonResult ----

    @Test fun `postJsonResult returns HttpResult Ok for 201 response and verifies request body`() = runTest {
        // Given - server queued to respond 201
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":99}"""))
        val sentBody = """{"name":"Sgt. Pepper"}"""

        // When - the body is posted
        val result = client.postJsonResult(url(), sentBody)

        // Then - response is Ok
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals(99, ok.body.getInt("id"))
        assertEquals(201, ok.statusCode)

        // Then - request body matches
        val request = server.takeRequest()
        assertEquals(sentBody, request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.contains("application/json"))
    }

    @Test fun `postJsonResult returns HttpResult ClientError for 400 response`() = runTest {
        // Given - server responds 400
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        // When - the body is posted
        val result = client.postJsonResult(url(), """{}""")

        // Then - result is ClientError carrying the 400 status
        assertEquals(400, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `postJsonResult returns HttpResult ServerError for 500 response`() = runTest {
        // Given - server responds 500
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        // When - the body is posted
        val result = client.postJsonResult(url(), """{}""")

        // Then - result is ServerError carrying the 500 status
        assertTrue(result is HttpResult.ServerError)
        assertEquals(500, (result as HttpResult.ServerError).statusCode)
    }

    // ---- postJsonArrayResult ----

    @Test fun `postJsonArrayResult returns HttpResult Ok with JSONArray for 200 response`() = runTest {
        // Given - server responds 200 with a JSON array
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"track":"Come Together"}]"""))

        // When - the body is posted
        val result = client.postJsonArrayResult(url(), """{"album":"Abbey Road"}""")

        // Then - result is Ok wrapping the parsed JSONArray
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals(1, ok.body.length())
        assertEquals("Come Together", ok.body.getJSONObject(0).getString("track"))
    }

    // ---- Edge case: empty body on 200 ----

    @Test fun `fetchJsonResult returns NetworkError when 200 body is empty string`() = runTest {
        // Given - server returns 200 with empty body
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        // When - the JSON result is fetched
        val result = client.fetchJsonResult(url())

        // Then - empty string is not valid JSON, should be NetworkError
        assertTrue("Expected NetworkError for empty body, got $result", result is HttpResult.NetworkError)
    }

    // ---- Edge case: 301 redirect ----

    @Test fun `fetchRedirectUrlResult returns Location header for 301 redirect`() = runTest {
        // Given - server returns 301 (permanent redirect)
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", "https://cdn.example.com/image.jpg")
        )

        // When - the redirect URL is fetched
        val result = client.fetchRedirectUrlResult(url())

        // Then - Location header extracted, same as 307
        assertEquals("https://cdn.example.com/image.jpg", (result as HttpResult.Ok).body)
    }

    // ---- Edge case: 429 on POST methods (Retry-After parsing) ----

    @Test fun `postJsonResult returns RateLimited with Retry-After for 429`() = runTest {
        // Given - POST returns 429 with Retry-After
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "10")
                .setBody("Too Many Requests")
        )

        // When - the body is posted
        val result = client.postJsonResult(url(), """{"test":true}""")

        // Then - retryAfterMs = 10 * 1000 = 10000
        assertTrue(result is HttpResult.RateLimited)
        assertEquals(10000L, (result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `postJsonArrayResult returns HttpResult RateLimited for 429 response`() = runTest {
        // Given - server responds 429
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))

        // When - the body is posted
        val result = client.postJsonArrayResult(url(), """{}""")

        // Then - result is RateLimited
        assertTrue(result is HttpResult.RateLimited)
    }
}
