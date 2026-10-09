package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The startup check of each keyless provider's host, against a local server standing in for the
 * upstream. The server answers every request, but only a request carrying [USER_AGENT] is recorded
 * or obeys the scripted behaviour: a loopback port is open to every process on the machine, and
 * some probe each new one, so a stranger's request must be neither a probe nor a turn at the script.
 */
class HostReachabilityTest {

    private lateinit var upstream: HttpServer
    private val probes = ConcurrentLinkedQueue<String>()
    private val probeArrived = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val http = HttpClient.newHttpClient()

    @Before fun startUpstream() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        upstream.createContext("/") { exchange ->
            if (exchange.requestHeaders.getFirst("User-Agent") == USER_AGENT) {
                probes.add("${exchange.requestMethod} ${exchange.requestURI}")
                probeArrived.countDown()
                respondAsScripted(exchange)
            } else {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        upstream.start()
    }

    @After fun stopUpstream() {
        released.countDown()
        upstream.stop(0)
    }

    /**
     * `/status/<code>` answers that code; `/delay/<ms>` answers 200 after that long;
     * `/redirect-to-403` redirects to it; `/hang` holds the connection open until teardown. The hung client is gone by then, so the write may throw.
     */
    private fun respondAsScripted(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        runCatching {
            when {
                path == "/hang" -> released.await(10, TimeUnit.SECONDS)
                path.startsWith("/delay/") -> {
                    Thread.sleep(path.substringAfterLast('/').toLong())
                    exchange.sendResponseHeaders(200, -1)
                }
                path == "/redirect-to-403" -> {
                    exchange.responseHeaders.add("Location", "$base/status/403")
                    exchange.sendResponseHeaders(302, -1)
                }
                else -> exchange.sendResponseHeaders(path.substringAfterLast('/').toInt(), -1)
            }
            exchange.close()
        }
    }

    private val base get() = "http://127.0.0.1:${upstream.address.port}"

    private fun reachability(timeoutMs: Long = 2_000, clock: Clock = Clock.systemUTC()) =
        HostReachability(emptyMap(), USER_AGENT, timeoutMs, clock)

    private fun probeStatus(status: Int) = reachability().probe("$base/status/$status")

    @Test fun `any answer except 401 and 403 is reachable, throttling and server errors included`() {
        // Given - an upstream that answers each of 200, 404, 429 and 500
        val statuses = listOf(200, 404, 429, 500)

        // When - probing a route that answers each status
        val rows = statuses.associateWith { probeStatus(it) }

        // Then - every one is reachable and records the status it got
        statuses.forEach { status ->
            assertEquals("HTTP $status", ReachabilityVerdict.REACHABLE, rows.getValue(status).verdict)
            assertEquals(status, rows.getValue(status).httpStatus)
        }
    }

    @Test fun `401 and 403 mean the host refused this one`() {
        // Given - an upstream that answers 401 and 403

        // When - probing a route that answers each
        val rows = listOf(401, 403).associateWith { probeStatus(it) }

        // Then - both are refused, with the status the upstream gave
        listOf(401, 403).forEach { status ->
            assertEquals("HTTP $status", ReachabilityVerdict.REFUSED, rows.getValue(status).verdict)
            assertEquals(status, rows.getValue(status).httpStatus)
        }
    }

    @Test fun `a closed port is unreachable and carries no status`() {
        // Given - a port that was free a moment ago and has nothing listening
        val closedPort = ServerSocket(0).use { it.localPort }

        // When - probing a route on it
        val row = reachability().probe("http://127.0.0.1:$closedPort/artist/399")

        // Then - the connection failure is unreachable, with no status to report
        assertEquals(ReachabilityVerdict.UNREACHABLE, row.verdict)
        assertNull(row.httpStatus)
    }

    @Test fun `an upstream that never answers is unreachable once the timeout passes`() {
        // Given - an upstream that holds the connection open, and a 300 ms probe timeout
        val probe = reachability(timeoutMs = 300)

        // When - probing the hanging route
        val started = System.nanoTime()
        val row = probe.probe("$base/hang")
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        // Then - it gives up as unreachable well before the upstream would have answered
        assertEquals(ReachabilityVerdict.UNREACHABLE, row.verdict)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test fun `a redirect is the answer, and its target is not followed`() {
        // Given - a route that redirects to one that would answer 403
        // When - probing the redirecting route
        val row = reachability().probe("$base/redirect-to-403")

        // Then - the redirect itself is the verdict, so the target's 403 does not refuse this host
        assertEquals(ReachabilityVerdict.REACHABLE, row.verdict)
        assertEquals(302, row.httpStatus)
    }

    @Test fun `a probe is one GET carrying the demo's User-Agent, recorded with host and UTC time`() {
        // Given - a clock fixed at a time that has sub-second parts
        val clock = Clock.fixed(Instant.parse("2026-10-09T15:24:07.123Z"), ZoneOffset.UTC)

        // When - probing a route
        val row = reachability(clock = clock).probe("$base/status/200")

        // Then - the upstream saw one GET, and the row names the host and the time to the second
        assertEquals(listOf("GET /status/200"), probes.toList())
        assertEquals("127.0.0.1", row.host)
        assertEquals("2026-10-09T15:24:07Z", row.checkedAt)
    }

    private class StubProvider(override val id: String, override val requiresApiKey: Boolean) : EnrichmentProvider {
        override val displayName = id
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.NotFound(type, id)
    }

    private fun startServerWith(providers: List<EnrichmentProvider>, hostReachability: HostReachability): Int {
        val engine = EnrichmentEngine.Builder()
            .also { builder -> providers.forEach { builder.addProvider(it) } }
            .cache(InMemoryEnrichmentCache())
            .build()
        return startServer(
            AtomicReference(engine),
            AtomicReference(CacheMode.NETWORK_FIRST),
            { engine },
            ApiKeyConfig(),
            0,
            hostReachability = hostReachability,
        )
    }

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun providerRow(port: Int, id: String): JsonObject =
        Json.parseToJsonElement(get(port, "/api/providers").body())
            .jsonObject["providers"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == id }

    private fun reachabilityOf(port: Int, id: String): JsonObject = providerRow(port, id)["reachability"]!!.jsonObject

    /** The probe records its verdict after the upstream has answered, so the endpoint needs a moment. */
    private fun awaitVerdict(port: Int, id: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val row = reachabilityOf(port, id)
            if (row["verdict"]!!.jsonPrimitive.content != "UNCHECKED") return row
            Thread.sleep(20)
        }
        throw AssertionError("no verdict for $id within 5s")
    }

    @Test fun `startup waits at most the cap for the probes and reports UNCHECKED for one still running`() {
        // Given - a keyless provider whose probe target holds the connection open, a 30 s request
        // timeout, and a 600 ms cap on the startup wait
        val lines = ConcurrentLinkedQueue<String>()
        val hostReachability = HostReachability(
            mapOf("stub-keyless" to "$base/hang"),
            USER_AGENT,
            30_000,
            startupWaitMs = 600,
            log = { lines.add(it) },
        )

        // When - the server starts
        val startedAt = System.nanoTime()
        val port = startServerWith(listOf(StubProvider("stub-keyless", requiresApiKey = false)), hostReachability)
        val startupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        // Then - the probe reached the upstream, startup returned at the cap, and the row is unchecked
        assertTrue("the probe never reached the upstream", probeArrived.await(5, TimeUnit.SECONDS))
        assertTrue("startServer took ${startupMs}ms, under the 600ms cap", startupMs >= 500)
        assertTrue("startServer took ${startupMs}ms, over the cap", startupMs < 3_000)
        assertEquals(200, get(port, "/api/health").statusCode())
        assertEquals("UNCHECKED", reachabilityOf(port, "stub-keyless")["verdict"]!!.jsonPrimitive.content)
        assertEquals(listOf("host reachability: stub-keyless UNCHECKED (no answer within 600ms)"), lines.toList())
    }

    @Test fun `a probe that returns after the startup wait does not change the verdict`() {
        // Given - a server started with a probe still out at its 400 ms cap
        val hostReachability = HostReachability(mapOf("stub-keyless" to "$base/hang"), USER_AGENT, 30_000, startupWaitMs = 400)
        val port = startServerWith(listOf(StubProvider("stub-keyless", requiresApiKey = false)), hostReachability)

        // When - the upstream drops the connection after startup, which the probe reads as a failure
        released.countDown()
        Thread.sleep(500)

        // Then - the row stays unchecked, since verdicts are fixed once the instance has started
        assertEquals("UNCHECKED", reachabilityOf(port, "stub-keyless")["verdict"]!!.jsonPrimitive.content)
    }

    @Test fun `the first providers read right after startServer already carries a fast upstream's verdict`() {
        // Given - a keyless provider whose probe target answers 403 at once
        val hostReachability = HostReachability(mapOf("stub-keyless" to "$base/status/403"), USER_AGENT, 2_000)

        // When - the server starts and providers is read straight away, with no waiting for a verdict
        val port = startServerWith(listOf(StubProvider("stub-keyless", requiresApiKey = false)), hostReachability)
        val row = reachabilityOf(port, "stub-keyless")

        // Then - the verdict is already settled, because the probe ran before the server listened
        assertEquals("REFUSED", row["verdict"]!!.jsonPrimitive.content)
        assertEquals(403, row["httpStatus"]!!.jsonPrimitive.int)
    }

    @Test fun `the probes run in parallel, so startup takes one probe's time rather than the sum`() {
        // Given - four keyless providers whose targets each answer after 500 ms
        val ids = (1..4).map { "stub-$it" }
        val hostReachability = HostReachability(ids.associateWith { "$base/delay/500" }, USER_AGENT, 2_000)

        // When - the server starts
        val startedAt = System.nanoTime()
        val port = startServerWith(ids.map { StubProvider(it, requiresApiKey = false) }, hostReachability)
        val startupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        // Then - startup took well under the 2000 ms that four in a row would need, and all four settled
        assertTrue("startServer took ${startupMs}ms", startupMs < 1_500)
        ids.forEach { id -> assertEquals(id, "REACHABLE", reachabilityOf(port, id)["verdict"]!!.jsonPrimitive.content) }
    }

    @Test fun `startup logs one line per probed provider with its verdict and the failure's exception class`() {
        // Given - providers whose targets answer 403, answer 200 and never answer, and a keyed one
        val lines = ConcurrentLinkedQueue<String>()
        val hostReachability = HostReachability(
            mapOf(
                "stub-refused" to "$base/status/403",
                "stub-ok" to "$base/status/200",
                "stub-silent" to "$base/hang",
                "stub-keyed" to "$base/status/200",
            ),
            USER_AGENT,
            300,
            log = { lines.add(it) },
        )

        // When - the server starts
        startServerWith(
            listOf(
                StubProvider("stub-refused", requiresApiKey = false),
                StubProvider("stub-ok", requiresApiKey = false),
                StubProvider("stub-silent", requiresApiKey = false),
                StubProvider("stub-keyed", requiresApiKey = true),
            ),
            hostReachability,
        )

        // Then - each probed provider has one line, naming the status or the exception, and the keyed one has none
        assertEquals(
            setOf(
                "host reachability: stub-refused REFUSED (HTTP 403 from 127.0.0.1)",
                "host reachability: stub-ok REACHABLE (HTTP 200 from 127.0.0.1)",
                "host reachability: stub-silent UNREACHABLE (HttpTimeoutException: request timed out)",
            ),
            lines.toSet(),
        )
        assertEquals(3, lines.size)
    }

    @Test fun `providers carries each keyless provider's verdict as a reachability object`() {
        // Given - a keyless provider whose probe target answers 403
        val hostReachability = HostReachability(mapOf("stub-keyless" to "$base/status/403"), USER_AGENT, 2_000)

        // When - the server starts and the providers endpoint is read once the probe has returned
        val port = startServerWith(listOf(StubProvider("stub-keyless", requiresApiKey = false)), hostReachability)
        val row = awaitVerdict(port, "stub-keyless")

        // Then - the raw wire object names the verdict, the status, the host and a UTC time
        assertEquals("REFUSED", row["verdict"]!!.jsonPrimitive.content)
        assertEquals(403, row["httpStatus"]!!.jsonPrimitive.int)
        assertEquals("127.0.0.1", row["host"]!!.jsonPrimitive.content)
        assertTrue(row["checkedAt"]!!.jsonPrimitive.content.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")))
    }

    @Test fun `a keyed provider is never probed and reads unchecked with null fields`() {
        // Given - a keyed provider whose id has a probe target, beside a keyless one that is probed
        val hostReachability = HostReachability(
            mapOf("stub-keyed" to "$base/status/403", "stub-keyless" to "$base/status/200"),
            USER_AGENT,
            2_000,
        )

        // When - the server starts and the keyless probe has returned
        val port = startServerWith(
            listOf(StubProvider("stub-keyed", requiresApiKey = true), StubProvider("stub-keyless", requiresApiKey = false)),
            hostReachability,
        )
        awaitVerdict(port, "stub-keyless")

        // Then - the upstream saw exactly the keyless probe, and the keyed row is blank
        assertEquals(listOf("GET /status/200"), probes.toList())
        val keyed = reachabilityOf(port, "stub-keyed")
        assertEquals("UNCHECKED", keyed["verdict"]!!.jsonPrimitive.content)
        listOf("httpStatus", "host", "checkedAt").forEach { field ->
            assertEquals(field, JsonNull, keyed[field])
        }
    }

    private companion object {
        const val USER_AGENT = "musicmeta-demo-reachability-test/1.0"
    }
}
