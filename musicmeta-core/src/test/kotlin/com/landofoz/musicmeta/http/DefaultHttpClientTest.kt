package com.landofoz.musicmeta.http

import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DefaultHttpClientTest {

    // --- HttpResult sealed class type tests ---

    @Test fun `HttpResult Ok carries body and statusCode`() {
        // Given / When
        val json = JSONObject("""{"name":"test"}""")
        val result = HttpResult.Ok(json, 200)

        // Then
        assertEquals("test", result.body.getString("name"))
        assertEquals(200, result.statusCode)
    }

    @Test fun `HttpResult Ok defaults statusCode to 200`() {
        // Given / When
        val result = HttpResult.Ok(JSONObject())

        // Then
        assertEquals(200, result.statusCode)
    }

    @Test fun `HttpResult ClientError carries statusCode and body`() {
        // Given / When
        val result = HttpResult.ClientError(404, "Not Found")

        // Then
        assertEquals(404, result.statusCode)
        assertEquals("Not Found", result.body)
    }

    @Test fun `HttpResult ClientError body defaults to null`() {
        // Given / When
        val result = HttpResult.ClientError(401)

        // Then
        assertEquals(401, result.statusCode)
        assertNull(result.body)
    }

    @Test fun `HttpResult ServerError carries statusCode`() {
        // Given / When
        val result = HttpResult.ServerError(500, "Internal Server Error")

        // Then
        assertEquals(500, result.statusCode)
        assertEquals("Internal Server Error", result.body)
    }

    @Test fun `HttpResult RateLimited carries retryAfterMs`() {
        // Given / When
        val result = HttpResult.RateLimited(5000L)

        // Then
        assertEquals(5000L, result.retryAfterMs)
    }

    @Test fun `HttpResult RateLimited retryAfterMs defaults to null`() {
        // Given / When
        val result = HttpResult.RateLimited()

        // Then
        assertNull(result.retryAfterMs)
    }

    @Test fun `HttpResult NetworkError carries message and cause`() {
        // Given
        val cause = java.io.IOException("connection reset")

        // When
        val result = HttpResult.NetworkError("Network failure", cause)

        // Then
        assertEquals("Network failure", result.message)
        assertSame(cause, result.cause)
    }

    @Test fun `HttpResult NetworkError cause defaults to null`() {
        // Given / When
        val result = HttpResult.NetworkError("timeout")

        // Then
        assertNull(result.cause)
    }

    // --- FakeHttpClient fetchJsonResult tests ---

    @Test fun `FakeHttpClient fetchJsonResult returns configured result`() = runTest {
        // Given
        val fake = FakeHttpClient()
        val json = JSONObject("""{"id":42}""")
        fake.givenHttpResult("api.example.com", HttpResult.Ok(json))

        // When
        val result = fake.fetchJsonResult("https://api.example.com/data")

        // Then
        assertTrue(result is HttpResult.Ok)
        assertEquals(42, (result as HttpResult.Ok).body.getInt("id"))
    }

    @Test fun `FakeHttpClient fetchJsonResult returns configured error`() = runTest {
        // Given
        val fake = FakeHttpClient()
        fake.givenHttpResult("api.example.com", HttpResult.ClientError(404))

        // When
        val result = fake.fetchJsonResult("https://api.example.com/missing")

        // Then
        assertTrue(result is HttpResult.ClientError)
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `FakeHttpClient fetchJsonResult falls back to fetchJson behavior`() = runTest {
        // Given — configure fetchJson response but no fetchJsonResult response
        val fake = FakeHttpClient()
        fake.givenJsonResponse("api.example.com", """{"fallback":true}""")

        // When
        val result = fake.fetchJsonResult("https://api.example.com/data")

        // Then — falls back to Ok wrapping fetchJson result
        assertTrue(result is HttpResult.Ok)
        assertTrue((result as HttpResult.Ok).body.getBoolean("fallback"))
    }

    @Test fun `FakeHttpClient fetchJsonResult returns a 404 when no response configured`() = runTest {
        // Given — no responses configured
        val fake = FakeHttpClient()

        // When
        val result = fake.fetchJsonResult("https://api.example.com/unknown")

        // Then — a 404: the fake has nothing at that address. Deliberately not a transient variant,
        // which providers treat as a retryable Error — that would make every test that merely omits
        // a stub assert the transient path instead of the empty-result path it means to test.
        assertTrue(result is HttpResult.ClientError)
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `FakeHttpClient fetchJsonResult records URL`() = runTest {
        // Given
        val fake = FakeHttpClient()
        fake.givenHttpResult("api", HttpResult.RateLimited(1000L))

        // When
        fake.fetchJsonResult("https://api.example.com/rate-limited")

        // Then
        assertTrue(fake.requestedUrls.contains("https://api.example.com/rate-limited"))
    }
}
