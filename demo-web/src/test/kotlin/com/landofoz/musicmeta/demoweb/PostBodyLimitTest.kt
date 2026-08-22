package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded reads on `POST /api/config` and `POST /api/invalidate` bodies. A caller can lie about
 * `Content-Length` or omit it (chunked transfer), so both halves of `readBodyCapped` are pinned:
 * the declared-length refusal, which must fire before any byte is buffered, and the actual-stream
 * cap, which is the only thing standing between a chunked body and an unbounded `readBytes()`.
 *
 * `HttpURLConnection`/`java.net.http.HttpClient` both refuse to send a body whose actual length
 * disagrees with a hand-set `Content-Length`, so these write raw HTTP/1.1 over a socket instead —
 * the only way to reproduce a lying header or a truly unbounded chunked body from a JVM client.
 */
class PostBodyLimitTest {

    private class CountingProvider : EnrichmentProvider {
        val calls = AtomicInteger(0)
        override val id = "stub-genre"
        override val displayName = "Stub Genre"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            calls.incrementAndGet()
            return EnrichmentResult.Success(type, EnrichmentData.Metadata(genres = listOf("rock")), id, 0.9f)
        }
    }

    private fun startTestServer(provider: CountingProvider): Int {
        val engine = EnrichmentEngine.Builder()
            .addProvider(provider)
            .cache(InMemoryEnrichmentCache())
            .build()
        val port = (20000..40000).random()
        startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), port)
        return port
    }

    /**
     * Sends a raw HTTP/1.1 request whose `Content-Length` header and actual body may disagree, and
     * whose body may be streamed without ever finishing — a plain socket write is what lets a test
     * claim a length no standard client would let it claim.
     *
     * A chunked body is always properly terminated (`0\r\n\r\n`), even when it is the oversized
     * case: `com.sun.net.httpserver`'s `HttpExchange` drains any unread request body once the
     * handler's response has gone out, and a `ChunkedInputStream` missing its terminator hangs
     * that drain forever, wedging the test rather than asserting anything. That risk is specific
     * to chunked framing — a declared `Content-Length` the actual bytes fall short of does not
     * trigger the same drain-on-close hang, which is what lets the Content-Length cases below stay
     * genuinely short: only the declared header, never the read, is what a removed pre-check would
     * let through.
     */
    private fun rawPost(port: Int, path: String, declaredLength: Int?, bodyToSend: ByteArray): String {
        Socket("localhost", port).use { socket ->
            val lengthHeader = declaredLength?.let { "Content-Length: $it\r\n" } ?: "Transfer-Encoding: chunked\r\n"
            val head = "POST $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n$lengthHeader\r\n"
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            if (declaredLength == null) {
                val chunkHeader = "${bodyToSend.size.toString(16)}\r\n"
                socket.getOutputStream().write(chunkHeader.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().write(bodyToSend)
                socket.getOutputStream().write("\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            } else {
                socket.getOutputStream().write(bodyToSend)
            }
            socket.getOutputStream().flush()
            return socket.getInputStream().bufferedReader().readLine() ?: ""
        }
    }

    private fun statusOf(statusLine: String): Int = statusLine.split(" ")[1].toInt()

    @Test fun `invalidate POST over the Content-Length cap is refused before decoding`() {
        // Given - a body far under the declared Content-Length, so the server can only have
        // rejected on the header — it never got enough bytes to decode anything
        val provider = CountingProvider()
        val port = startTestServer(provider)
        val oversizedDeclaration = 64 * 1024 + 1

        // When - POSTing to /api/invalidate with a Content-Length above the cap
        val status = statusOf(rawPost(port, "/api/invalidate", oversizedDeclaration, "{}".toByteArray()))

        // Then - refused with 413, and no engine call was ever made
        assertEquals(413, status)
        assertEquals(0, provider.calls.get())
    }

    @Test fun `invalidate POST with an oversized chunked body is refused`() {
        // Given - a chunked body (no Content-Length) larger than the cap
        val provider = CountingProvider()
        val port = startTestServer(provider)
        val oversizedBody = ByteArray(64 * 1024 + 1) { '0'.code.toByte() }

        // When - POSTing to /api/invalidate with no Content-Length and a body over the cap
        val status = statusOf(rawPost(port, "/api/invalidate", null, oversizedBody))

        // Then - refused with 413, not 200/500 from an unbounded readBytes() decoding garbage
        assertEquals(413, status)
    }

    @Test fun `config POST over the Content-Length cap is refused before decoding`() {
        // Given - a config server; the maintainer gate is off by default so the body cap is what's
        // under test here, not the secret check
        val provider = CountingProvider()
        val port = startTestServer(provider)
        val oversizedDeclaration = 64 * 1024 + 1

        // When - POSTing to /api/config with a Content-Length above the cap
        val status = statusOf(rawPost(port, "/api/config", oversizedDeclaration, "{}".toByteArray()))

        // Then - refused with 413 before any JSON decode is attempted
        assertEquals(413, status)
    }

    @Test fun `config POST with an oversized chunked body is refused`() {
        // Given - a config server and a chunked body over the cap
        val provider = CountingProvider()
        val port = startTestServer(provider)
        val oversizedBody = ByteArray(64 * 1024 + 1) { '0'.code.toByte() }

        // When - POSTing to /api/config with no Content-Length and a body over the cap
        val status = statusOf(rawPost(port, "/api/config", null, oversizedBody))

        // Then - refused with 413
        assertEquals(413, status)
    }
}
