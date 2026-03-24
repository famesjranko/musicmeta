package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.http.HttpResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
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

    // ---- fetchJson ----

    @Test fun `fetchJson returns JSONObject for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"test"}"""))

        // When
        val result = client.fetchJson(url())

        // Then
        assertNotNull(result)
        assertEquals("test", result!!.getString("name"))
    }

    @Test fun `fetchJson returns null for 500 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        // When
        val result = client.fetchJson(url())

        // Then
        assertNull(result)
    }

    @Test fun `fetchJson returns null for invalid JSON body`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        // When
        val result = client.fetchJson(url())

        // Then
        assertNull(result)
    }

    // ---- fetchJsonArray ----

    @Test fun `fetchJsonArray returns JSONArray for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":1},{"id":2}]"""))

        // When
        val result = client.fetchJsonArray(url())

        // Then
        assertNotNull(result)
        assertEquals(2, result!!.length())
        assertEquals(1, result.getJSONObject(0).getInt("id"))
    }

    @Test fun `fetchJsonArray returns null for 404 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        // When
        val result = client.fetchJsonArray(url())

        // Then
        assertNull(result)
    }

    // ---- fetchBody ----

    @Test fun `fetchBody returns raw string body for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("raw body text"))

        // When
        val result = client.fetchBody(url())

        // Then
        assertEquals("raw body text", result)
    }

    @Test fun `fetchBody returns null for 500 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        // When
        val result = client.fetchBody(url())

        // Then
        assertNull(result)
    }

    // ---- fetchRedirectUrl ----

    @Test fun `fetchRedirectUrl returns Location header value for 307 response`() = runTest {
        // Given
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", "https://example.com/redirected")
        )

        // When
        val result = client.fetchRedirectUrl(url())

        // Then
        assertEquals("https://example.com/redirected", result)
    }

    @Test fun `fetchRedirectUrl returns original URL for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val requestUrl = url()

        // When
        val result = client.fetchRedirectUrl(requestUrl)

        // Then
        assertEquals(requestUrl, result)
    }

    @Test fun `fetchRedirectUrl returns null for 500 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        // When
        val result = client.fetchRedirectUrl(url())

        // Then
        assertNull(result)
    }

    // ---- postJson ----

    @Test fun `postJson returns JSONObject for 200 response and request body is transmitted`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"created":true}"""))
        val sentBody = """{"title":"Abbey Road"}"""

        // When
        val result = client.postJson(url(), sentBody)

        // Then — response is parsed
        assertNotNull(result)
        assertTrue(result!!.getBoolean("created"))

        // Then — request body was transmitted correctly
        val request = server.takeRequest()
        assertEquals(sentBody, request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.contains("application/json"))
    }

    @Test fun `postJson returns null for 400 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        // When
        val result = client.postJson(url(), """{}""")

        // Then
        assertNull(result)
    }

    // ---- postJsonArray ----

    @Test fun `postJsonArray returns JSONArray for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"status":"ok"}]"""))

        // When
        val result = client.postJsonArray(url(), """{"query":"all"}""")

        // Then
        assertNotNull(result)
        assertEquals(1, result!!.length())
    }

    // ---- User-Agent header ----

    @Test fun `User-Agent header is sent on every request`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        // When
        client.fetchJson(url())

        // Then
        val request = server.takeRequest()
        assertEquals("TestAgent/1.0", request.getHeader("User-Agent"))
    }

    // ---- Gzip decompression ----

    @Test fun `fetchJson decompresses gzip-encoded response transparently`() = runTest {
        // Given — compress a JSON body with GZIP
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

        // When
        val result = client.fetchJson(url())

        // Then — OkHttp decompresses transparently; JSON is parsed correctly
        assertNotNull(result)
        assertEquals("The Beatles", result!!.getString("artist"))
    }

    // ---- fetchJsonResult ----

    @Test fun `fetchJsonResult returns HttpResult Ok for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"album":"Let It Be"}"""))

        // When
        val result = client.fetchJsonResult(url())

        // Then
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals("Let It Be", ok.body.getString("album"))
        assertEquals(200, ok.statusCode)
    }

    @Test fun `fetchJsonResult returns HttpResult RateLimited with retryAfterMs for 429 with Retry-After header`() = runTest {
        // Given
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "5")
                .setBody("rate limited")
        )

        // When
        val result = client.fetchJsonResult(url())

        // Then
        assertTrue(result is HttpResult.RateLimited)
        assertEquals(5000L, (result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `fetchJsonResult returns HttpResult RateLimited with null retryAfterMs for 429 without Retry-After header`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))

        // When
        val result = client.fetchJsonResult(url())

        // Then
        assertTrue(result is HttpResult.RateLimited)
        assertNull((result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `fetchJsonResult returns HttpResult ClientError for 404 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        // When
        val result = client.fetchJsonResult(url())

        // Then
        assertTrue(result is HttpResult.ClientError)
        val err = result as HttpResult.ClientError
        assertEquals(404, err.statusCode)
        assertEquals("Not Found", err.body)
    }

    @Test fun `fetchJsonResult returns HttpResult ServerError for 503 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        // When
        val result = client.fetchJsonResult(url())

        // Then
        assertTrue(result is HttpResult.ServerError)
        val err = result as HttpResult.ServerError
        assertEquals(503, err.statusCode)
        assertEquals("Service Unavailable", err.body)
    }

    @Test fun `fetchJsonResult returns HttpResult NetworkError for invalid JSON in 200 body`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("not valid json"))

        // When
        val result = client.fetchJsonResult(url())

        // Then
        assertTrue(result is HttpResult.NetworkError)
        assertTrue((result as HttpResult.NetworkError).message.contains("JSON parse error"))
    }

    @Test fun `fetchJsonResult returns HttpResult NetworkError when server is unreachable`() = runTest {
        // Given — shut down the server before making a request
        server.shutdown()

        // When
        val result = client.fetchJsonResult("http://localhost:1/unreachable")

        // Then
        assertTrue(result is HttpResult.NetworkError)
    }

    // ---- fetchJsonArrayResult ----

    @Test fun `fetchJsonArrayResult returns HttpResult Ok with JSONArray for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":1},{"id":2}]"""))

        // When
        val result = client.fetchJsonArrayResult(url())

        // Then
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals(2, ok.body.length())
    }

    @Test fun `fetchJsonArrayResult returns HttpResult ClientError for 400 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        // When
        val result = client.fetchJsonArrayResult(url())

        // Then
        assertTrue(result is HttpResult.ClientError)
        assertEquals(400, (result as HttpResult.ClientError).statusCode)
    }

    // ---- postJsonResult ----

    @Test fun `postJsonResult returns HttpResult Ok for 201 response and verifies request body`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":99}"""))
        val sentBody = """{"name":"Sgt. Pepper"}"""

        // When
        val result = client.postJsonResult(url(), sentBody)

        // Then — response is Ok
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals(99, ok.body.getInt("id"))
        assertEquals(201, ok.statusCode)

        // Then — request body matches
        val request = server.takeRequest()
        assertEquals(sentBody, request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.contains("application/json"))
    }

    @Test fun `postJsonResult returns HttpResult ServerError for 500 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        // When
        val result = client.postJsonResult(url(), """{}""")

        // Then
        assertTrue(result is HttpResult.ServerError)
        assertEquals(500, (result as HttpResult.ServerError).statusCode)
    }

    // ---- postJsonArrayResult ----

    @Test fun `postJsonArrayResult returns HttpResult Ok with JSONArray for 200 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"track":"Come Together"}]"""))

        // When
        val result = client.postJsonArrayResult(url(), """{"album":"Abbey Road"}""")

        // Then
        assertTrue(result is HttpResult.Ok)
        val ok = result as HttpResult.Ok
        assertEquals(1, ok.body.length())
        assertEquals("Come Together", ok.body.getJSONObject(0).getString("track"))
    }

    // ---- Edge case: empty body on 200 ----

    @Test fun `fetchJsonResult returns NetworkError when 200 body is empty string`() = runTest {
        // Given — server returns 200 with empty body
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        // When
        val result = client.fetchJsonResult(url())

        // Then — empty string is not valid JSON, should be NetworkError
        assertTrue("Expected NetworkError for empty body, got $result", result is HttpResult.NetworkError)
    }

    @Test fun `fetchJson returns null when 200 body is empty string`() = runTest {
        // Given — server returns 200 with empty body
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        // When
        val result = client.fetchJson(url())

        // Then — empty string is not valid JSON object
        assertNull(result)
    }

    // ---- Edge case: 301 redirect ----

    @Test fun `fetchRedirectUrl returns Location header for 301 redirect`() = runTest {
        // Given — server returns 301 (permanent redirect)
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", "https://cdn.example.com/image.jpg")
        )

        // When
        val result = client.fetchRedirectUrl(url())

        // Then — Location header extracted, same as 307
        assertEquals("https://cdn.example.com/image.jpg", result)
    }

    // ---- Edge case: 429 on POST methods (Retry-After parsing) ----

    @Test fun `postJsonResult returns RateLimited with Retry-After for 429`() = runTest {
        // Given — POST returns 429 with Retry-After
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "10")
                .setBody("Too Many Requests")
        )

        // When
        val result = client.postJsonResult(url(), """{"test":true}""")

        // Then — retryAfterMs = 10 * 1000 = 10000
        assertTrue(result is HttpResult.RateLimited)
        assertEquals(10000L, (result as HttpResult.RateLimited).retryAfterMs)
    }

    @Test fun `postJsonArrayResult returns HttpResult RateLimited for 429 response`() = runTest {
        // Given
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))

        // When
        val result = client.postJsonArrayResult(url(), """{}""")

        // Then
        assertTrue(result is HttpResult.RateLimited)
    }
}
