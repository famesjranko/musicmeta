package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Fires every verdict the schema pin can reach, on purpose.
 *
 * A watch nobody has seen fail is not a watch, and this one has exactly one job a green run cannot
 * demonstrate: telling a provider that moved a field apart from a provider that shed the request.
 * Each case below injects one fault and asserts which side of that line it lands on.
 *
 * The read timeout — the failure that dominates in the wild — is injected as the
 * `HttpResult.NetworkError` the client reports it as, rather than by waiting on a black hole; that
 * mapping is pinned by `DefaultHttpClientTimeoutRetryTest`. The closed-port case below goes through
 * the real client so the default arm is proved against a transport failure end to end, not only
 * against a hand-built result.
 */
class SchemaPinVerdictTest {

    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun target(
        url: String = "https://example.test/route?api_key=secret",
        paths: List<String> = listOf("artists[0].id", "artists[0].name"),
        shape: BodyShape = BodyShape.OBJECT,
    ) = SchemaTarget(
        provider = "musicbrainz",
        route = "artist search",
        url = url,
        requiredPaths = paths,
        shape = shape,
    )

    // --- DRIFT: the provider answered, and the answer moved ---

    @Test
    fun `a renamed field in a 200 body is drift, naming the path that moved`() = runTest {
        // Given - a 200 whose `id` has been renamed to `identifier`
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(
            pinned.url,
            HttpResult.Ok(JSONObject("""{"artists":[{"identifier":"abc","name":"Radiohead"}]}""")),
        )
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - it is drift, and it names the path rather than the value
        assertEquals(PinVerdict.Drift(listOf("artists[0].id")), verdict)
    }

    @Test
    fun `a field present but blank is drift`() = runTest {
        // Given - a 200 whose `id` arrives as an empty string
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(
            pinned.url,
            HttpResult.Ok(JSONObject("""{"artists":[{"id":"","name":"Radiohead"}]}""")),
        )
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - it is drift: a mapper cannot tell a blank from a field that never arrived
        assertEquals(PinVerdict.Drift(listOf("artists[0].id")), verdict)
    }

    @Test
    fun `a 200 carrying every pinned path is ok`() = runTest {
        // Given - a 200 whose body carries both pinned paths
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(
            pinned.url,
            HttpResult.Ok(JSONObject("""{"artists":[{"id":"abc","name":"Radiohead"}]}""")),
        )
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - nothing is reported
        assertEquals(PinVerdict.Ok, verdict)
    }

    // --- UNAVAILABLE: everything else, with no exceptions ---

    @Test
    fun `a 503 is unavailable, not drift`() = runTest {
        // Given - a route answering 503
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(pinned.url, HttpResult.ServerError(503))
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - it is unavailable, carrying the status as a label
        assertEquals(PinVerdict.Unavailable("http 503"), verdict)
    }

    @Test
    fun `a 429 is unavailable, not drift`() = runTest {
        // Given - a route answering 429
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(pinned.url, HttpResult.RateLimited(retryAfterMs = 1_000))
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - it is unavailable
        assertEquals(PinVerdict.Unavailable("http 429"), verdict)
    }

    @Test
    fun `a 404 is unavailable, not drift`() = runTest {
        // Given - a route answering 404, which is a client error rather than an empty document
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(pinned.url, HttpResult.ClientError(404))
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - it is unavailable
        assertEquals(PinVerdict.Unavailable("http 404"), verdict)
    }

    @Test
    fun `a read timeout is unavailable, not drift`() = runTest {
        // Given - the network error a read timeout reaches the caller as
        val pinned = target()
        val http = FakeHttpClient()
        http.givenHttpResult(pinned.url, HttpResult.NetworkError("Read timed out"))
        // When - the pin probes the route
        val verdict = probe(http, pinned)
        // Then - it is unavailable, which is what stops the most common failure reading as drift
        assertEquals(PinVerdict.Unavailable("transport Read timed out"), verdict)
    }

    @Test
    fun `a 200 carrying an HTML body at a JSON route is unavailable, not drift`() = runTest {
        // Given - a live route answering 200 with a maintenance page
        server.createContext("/pin") { exchange ->
            val body = "<html><body>maintenance</body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val pinned = target(url = "http://127.0.0.1:${server.address.port}/pin")
        // When - the pin probes it through the real client
        val verdict = probe(DefaultHttpClient("SchemaPinTest/1.0"), pinned)
        // Then - it is unavailable: an HTML body is a captive page far more often than a schema change
        assertTrue("expected Unavailable, got $verdict", verdict is PinVerdict.Unavailable)
    }

    @Test
    fun `a closed port is unavailable, not drift and not a crash`() = runTest {
        // Given - a route pointing at a port nothing listens on
        val pinned = target(url = "http://127.0.0.1:9/pin")
        // When - the pin probes it through the real client
        val verdict = probe(DefaultHttpClient("SchemaPinTest/1.0", timeoutMs = 500, maxRetries = 1), pinned)
        // Then - the default arm catches it without the run dying
        assertTrue("expected Unavailable, got $verdict", verdict is PinVerdict.Unavailable)
    }

    // --- the run's own exit ---

    @Test
    fun `a scan that read nothing fails`() {
        // Given - a run whose target list resolved to no routes at all
        val results = emptyList<PinResult>()
        // When - the run's findings are computed
        val findings = runFindings(results)
        // Then - it fails, rather than passing on having proved nothing
        assertEquals(1, findings.size)
        assertTrue(findings[0], findings[0].contains("scanned no routes"))
    }

    @Test
    fun `a run whose every route was unavailable fails`() {
        // Given - two routes, both unavailable
        val results = listOf(
            PinResult(target(), PinVerdict.Unavailable("http 503")),
            PinResult(target(), PinVerdict.Unavailable("transport Read timed out")),
        )
        // When - the run's findings are computed
        val findings = runFindings(results)
        // Then - it fails: a watch that saw nothing is blind, which is our problem
        assertEquals(1, findings.size)
        assertTrue(findings[0], findings[0].contains("every one of the 2 pinned routes was unavailable"))
    }

    @Test
    fun `a run with some routes unavailable and the rest ok passes`() {
        // Given - one shed route beside one healthy one
        val results = listOf(
            PinResult(target(), PinVerdict.Unavailable("transport Read timed out")),
            PinResult(target(), PinVerdict.Ok),
        )
        // When - the run's findings are computed
        val findings = runFindings(results)
        // Then - nothing fails: shedding at the measured rate would otherwise email every third day
        assertEquals(emptyList<String>(), findings)
    }

    @Test
    fun `a run with any drift fails, naming the route and the field`() {
        // Given - one drifted route beside one healthy one
        val results = listOf(
            PinResult(target(), PinVerdict.Drift(listOf("artists[0].id"))),
            PinResult(target(), PinVerdict.Ok),
        )
        // When - the run's findings are computed
        val findings = runFindings(results)
        // Then - it fails, and the message carries the provider, the route and the path
        assertEquals(1, findings.size)
        assertTrue(findings[0], findings[0].contains("musicbrainz"))
        assertTrue(findings[0], findings[0].contains("artist search"))
        assertTrue(findings[0], findings[0].contains("artists[0].id"))
    }

    // --- the secret this job runs with in scope ---

    @Test
    fun `neither a report line nor a finding carries the query string a key travels in`() {
        // Given - a drifted route whose URL carries a credential in its query string
        val pinned = target(url = "https://example.test/route?api_key=super-secret")
        val results = listOf(PinResult(pinned, PinVerdict.Drift(listOf("artists[0].id"))))
        // When - the run is reported
        val printed = reportLines(results) + runFindings(results)
        // Then - nothing printed carries the key, or the `?` that would introduce one
        assertTrue(printed.toString(), printed.none { it.contains("super-secret") })
        assertTrue(printed.toString(), printed.none { it.contains("?") })
    }

    // --- the path grammar the target lists are written in ---

    @Test
    fun `a path indexes arrays at the root and inside nested objects`() {
        // Given - an array-rooted body of the shape lrclib and listenbrainz answer with
        val body = org.json.JSONArray("""[{"trackName":"Karma Police","tags":[{"name":"rock"}]}]""")
        // When - each path is resolved
        // Then - both the root index and the nested index are found, and a missing one is not
        assertTrue(isPresent(body, "[0].trackName"))
        assertTrue(isPresent(body, "[0].tags[0].name"))
        assertTrue(!isPresent(body, "[0].tags[1].name"))
        assertTrue(!isPresent(body, "[1].trackName"))
    }

    @Test
    fun `a json null reads as absent, the same as a field that never arrived`() {
        // Given - a body whose pinned field is an explicit JSON null
        val body = JSONObject("""{"artists":[{"id":null,"name":"Radiohead"}]}""")
        // When - the body is classified
        val verdict = classifyBody(body, listOf("artists[0].id"))
        // Then - it is drift: a mapper cannot act on a null any better than on a missing key
        assertEquals(PinVerdict.Drift(listOf("artists[0].id")), verdict)
    }
}
