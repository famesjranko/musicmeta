package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * Port 0 asks the OS for a free port and [startServer] hands back the one it got, which is the
 * contract every test here binds on: a test that picks its own port instead is racing every other
 * process on the machine for it, and loses as a `BindException` in whichever test drew the clash.
 */
class ServerPortTest {

    private fun startTestServer(port: Int): Int {
        val engine = EnrichmentEngine.Builder().cache(InMemoryEnrichmentCache()).build()
        return startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), port)
    }

    private fun health(port: Int): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test fun `starting on port 0 returns the port the OS actually bound`() {
        // Given - a server asked for port 0, the ephemeral-port request
        val port = startTestServer(0)

        // When - calling health on the returned port
        val response = health(port)

        // Then - the port is a real bound one, not the 0 that was asked for, and it serves
        assertTrue("port 0 was handed back unresolved", port > 0)
        assertEquals(200, response.statusCode())
    }

    @Test fun `two servers started on port 0 never collide`() {
        // Given - two servers both asked for port 0
        val first = startTestServer(0)
        val second = startTestServer(0)

        // When - calling health on each
        val firstResponse = health(first)
        val secondResponse = health(second)

        // Then - the OS gave each its own port and both serve, which is what a self-picked port
        // cannot promise
        assertNotEquals(first, second)
        assertEquals(200, firstResponse.statusCode())
        assertEquals(200, secondResponse.statusCode())
    }
}
