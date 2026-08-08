package com.landofoz.musicmeta.http

import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DefaultHttpClientTest {

    // --- HttpResult sealed class type tests ---

    @Test fun `HttpResult Ok carries body and statusCode`() {
        // Given - a JSON body
        val json = JSONObject("""{"name":"test"}""")
        // When - an Ok is constructed with an explicit statusCode
        val result = HttpResult.Ok(json, 200)

        // Then - body and statusCode round-trip unchanged
        assertEquals("test", result.body.getString("name"))
        assertEquals(200, result.statusCode)
    }

    @Test fun `HttpResult Ok defaults statusCode to 200`() {
        // Given - an empty JSON body
        // When - an Ok is constructed with no explicit statusCode
        val result = HttpResult.Ok(JSONObject())

        // Then - statusCode defaults to 200
        assertEquals(200, result.statusCode)
    }

    @Test fun `HttpResult ClientError carries statusCode and body`() {
        // Given - a statusCode and body
        // When - a ClientError is constructed from them
        val result = HttpResult.ClientError(404, "Not Found")

        // Then - statusCode and body round-trip unchanged
        assertEquals(404, result.statusCode)
        assertEquals("Not Found", result.body)
    }

    @Test fun `HttpResult ClientError body defaults to null`() {
        // Given - only a statusCode
        // When - a ClientError is constructed with no explicit body
        val result = HttpResult.ClientError(401)

        // Then - body defaults to null
        assertEquals(401, result.statusCode)
        assertNull(result.body)
    }

    @Test fun `HttpResult ServerError carries statusCode`() {
        // Given - a statusCode and body
        // When - a ServerError is constructed from them
        val result = HttpResult.ServerError(500, "Internal Server Error")

        // Then - statusCode and body round-trip unchanged
        assertEquals(500, result.statusCode)
        assertEquals("Internal Server Error", result.body)
    }

    @Test fun `HttpResult RateLimited carries retryAfterMs`() {
        // Given - an explicit retryAfterMs
        // When - a RateLimited is constructed with it
        val result = HttpResult.RateLimited(5000L)

        // Then - retryAfterMs round-trips unchanged
        assertEquals(5000L, result.retryAfterMs)
    }

    @Test fun `HttpResult RateLimited retryAfterMs defaults to null`() {
        // Given - no retryAfterMs supplied
        // When - a RateLimited is constructed
        val result = HttpResult.RateLimited()

        // Then - retryAfterMs defaults to null
        assertNull(result.retryAfterMs)
    }

    @Test fun `HttpResult NetworkError carries message and cause`() {
        // Given - an underlying IOException
        val cause = java.io.IOException("connection reset")

        // When - a NetworkError is constructed with that cause
        val result = HttpResult.NetworkError("Network failure", cause)

        // Then - message and cause round-trip unchanged
        assertEquals("Network failure", result.message)
        assertSame(cause, result.cause)
    }

    @Test fun `HttpResult NetworkError cause defaults to null`() {
        // Given - a message and no cause
        // When - a NetworkError is constructed
        val result = HttpResult.NetworkError("timeout")

        // Then - cause defaults to null
        assertNull(result.cause)
    }

    // --- FakeHttpClient fetchJsonResult tests ---

    @Test fun `FakeHttpClient fetchJsonResult returns configured result`() = runTest {
        // Given - an Ok result stubbed for the host
        val fake = FakeHttpClient()
        val json = JSONObject("""{"id":42}""")
        fake.givenHttpResult("api.example.com", HttpResult.Ok(json))

        // When - a request is made to a URL under that host
        val result = fake.fetchJsonResult("https://api.example.com/data")

        // Then - the stubbed Ok result is returned
        assertTrue(result is HttpResult.Ok)
        assertEquals(42, (result as HttpResult.Ok).body.getInt("id"))
    }

    @Test fun `FakeHttpClient fetchJsonResult returns configured error`() = runTest {
        // Given - a ClientError result stubbed for the host
        val fake = FakeHttpClient()
        fake.givenHttpResult("api.example.com", HttpResult.ClientError(404))

        // When - a request is made to a URL under that host
        val result = fake.fetchJsonResult("https://api.example.com/missing")

        // Then - the stubbed ClientError result is returned
        assertTrue(result is HttpResult.ClientError)
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `FakeHttpClient fetchJsonResult falls back to a plain body stub`() = runTest {
        // Given - a plain body stub, with no HttpResult stub over it
        val fake = FakeHttpClient()
        fake.givenJsonResponse("api.example.com", """{"fallback":true}""")

        // When - a request is made to a URL under that host
        val result = fake.fetchJsonResult("https://api.example.com/data")

        // Then - falls back to Ok wrapping the stubbed body
        assertTrue(result is HttpResult.Ok)
        assertTrue((result as HttpResult.Ok).body.getBoolean("fallback"))
    }

    @Test fun `FakeHttpClient fetchJsonResult returns a 404 when no response configured`() = runTest {
        // Given - no responses configured
        val fake = FakeHttpClient()

        // When - a request is made to a URL with no stubbed response
        val result = fake.fetchJsonResult("https://api.example.com/unknown")

        // Then - a 404: the fake has nothing at that address. Deliberately not a transient variant,
        // which providers treat as a retryable Error — that would make every test that merely omits
        // a stub assert the transient path instead of the empty-result path it means to test.
        assertTrue(result is HttpResult.ClientError)
        assertEquals(404, (result as HttpResult.ClientError).statusCode)
    }

    @Test fun `FakeHttpClient fetchJsonResult records URL`() = runTest {
        // Given - a result stubbed for the host
        val fake = FakeHttpClient()
        fake.givenHttpResult("api", HttpResult.RateLimited(1000L))

        // When - a request is made to a URL under that host
        fake.fetchJsonResult("https://api.example.com/rate-limited")

        // Then - the requested URL is recorded
        assertTrue(fake.requestedUrls.contains("https://api.example.com/rate-limited"))
    }
}
