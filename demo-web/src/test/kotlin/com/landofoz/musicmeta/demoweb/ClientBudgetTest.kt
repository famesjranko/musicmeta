package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The per-client budget in front of the endpoints that can reach a provider.
 *
 * The admission gate bounds how much this instance runs at once; it says nothing about how much one
 * client may ask for over time, and "five at a time, forever" is still forever to an upstream whose
 * quota the demo is a guest of. So the assertions here are about one client's share, never about
 * concurrency — a bound that turned away a second visitor because a first one was mid-lookup would
 * satisfy nothing this exists for.
 */
class ClientBudgetTest {

    private companion object {
        const val SEARCH_PATH = "/api/search?kind=artist&q=Stereolab"
        val REFILL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(CLIENT_BUDGET_REFILL_MS)
    }

    private class SilentProvider : EnrichmentProvider {
        override val id = "stub-search"
        override val displayName = "Stub Search"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.NotFound(type, id)

        override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> =
            emptyList()
    }

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun startTestServer(): Int {
        val engine = EnrichmentEngine.Builder()
            .addProvider(SilentProvider())
            .cache(InMemoryEnrichmentCache())
            .build()
        val port = ServerSocket(0).use { it.localPort }
        startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), port)
        return port
    }

    private fun get(port: Int, path: String, client: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        if (client != null) request.header("X-Forwarded-For", client)
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun `a client past its burst is refused while another client is still served`() {
        // Given - one client that has spent its whole burst on lookups
        val port = startTestServer()
        val spent = (1..CLIENT_BUDGET_BURST).map { get(port, SEARCH_PATH, "203.0.113.7").statusCode() }
        assertEquals(List(CLIENT_BUDGET_BURST) { 200 }, spent)

        // When - that client asks once more, and a different client asks for the first time
        val refused = get(port, SEARCH_PATH, "203.0.113.7")
        val other = get(port, SEARCH_PATH, "198.51.100.4")

        // Then - only the client that spent its share is turned away, which is what makes this a
        // limit on one conversation rather than a limit on the instance
        assertEquals(429, refused.statusCode())
        assertEquals(200, other.statusCode())
    }

    @Test fun `a refusal is shaped like the admission gate's, so the page has one busy path`() {
        // Given - a client that has spent its whole burst
        val port = startTestServer()
        repeat(CLIENT_BUDGET_BURST) { get(port, SEARCH_PATH, "203.0.113.9") }

        // When - it asks once more
        val refused = get(port, SEARCH_PATH, "203.0.113.9")

        // Then - the status, the wait and a readable body are all where the gate puts them
        assertEquals(429, refused.statusCode())
        assertEquals("15", refused.headers().firstValue("Retry-After").orElse(""))
        assertTrue(json.decodeFromString(ApiError.serializer(), refused.body()).error.isNotBlank())
    }

    @Test fun `an endpoint that reaches no provider is not charged for`() {
        // Given - a client with a whole burst to spend
        val port = startTestServer()

        // When - it polls health far past that burst, as the page does while the backend warms
        val statuses = (1..CLIENT_BUDGET_BURST * 2).map { get(port, "/api/health", "203.0.113.11").statusCode() }

        // Then - none of it is refused: the budget exists to protect upstreams, and this endpoint
        // never leaves the process
        assertFalse(statuses.contains(429))
    }

    @Test fun `a second forged header line cannot mint a fresh budget`() {
        // Given - a client that splits its forgery across two header lines, varying the first while
        // the platform-appended entry stays what it is
        val port = startTestServer()
        fun ask(forged: String): Int {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$SEARCH_PATH"))
                .GET()
                .header("X-Forwarded-For", forged)
                .header("X-Forwarded-For", "203.0.113.42")
                .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
        }
        val spent = (1..CLIENT_BUDGET_BURST).map { ask("9.9.9.$it") }
        assertEquals(List(CLIENT_BUDGET_BURST) { 200 }, spent)

        // When - it asks once more under yet another first line
        val refused = ask("9.9.99.99")

        // Then - every line was one conversation: the charge followed the platform's entry, which
        // is the one entry no arrangement of the caller's own lines can move
        assertEquals(429, refused)
    }

    @Test fun `the platform's own entry is read, not the caller's claim`() {
        // Given - a header a client wrote for itself, with the address the platform saw appended
        val forwarded = "9.9.9.9, 203.0.113.7"

        // When - the request is charged to a client
        val key = clientKeyFrom(forwarded, peer = "10.0.0.1")

        // Then - it is the appended entry, so varying the claim cannot mint a second budget
        assertEquals("203.0.113.7", key)
    }

    @Test fun `the socket peer answers when nothing is in front of the process`() {
        // Given - no forwarding header at all, which is the local case
        // When - the request is charged to a client
        val key = clientKeyFrom(null, peer = "127.0.0.1")

        // Then - the peer is the client, because there is no proxy for it to be anything else
        assertEquals("127.0.0.1", key)
    }

    @Test fun `a spent budget comes back one request at a time`() {
        // Given - a budget whose whole burst has been spent at one instant
        val clock = AtomicLong(0)
        val budget = ClientBudget(nanoTime = clock::get)
        repeat(CLIENT_BUDGET_BURST) { assertTrue(budget.admit("203.0.113.7")) }
        assertFalse(budget.admit("203.0.113.7"))

        // When - exactly one refill period passes
        clock.set(REFILL_NANOS)

        // Then - one request is admitted and the next is not, so the client settles at the refill
        // rate rather than being handed its burst back
        assertTrue(budget.admit("203.0.113.7"))
        assertFalse(budget.admit("203.0.113.7"))
    }

    @Test fun `an idle client is never owed more than its burst`() {
        // Given - a budget untouched for far longer than it takes to refill
        val clock = AtomicLong(0)
        val budget = ClientBudget(nanoTime = clock::get)
        clock.set(REFILL_NANOS * CLIENT_BUDGET_BURST * 100)

        // When - the client spends everything it is owed
        repeat(CLIENT_BUDGET_BURST) { assertTrue(budget.admit("203.0.113.7")) }

        // Then - there is nothing beyond the burst, so time away cannot be saved up into a flood
        assertFalse(budget.admit("203.0.113.7"))
    }
}
