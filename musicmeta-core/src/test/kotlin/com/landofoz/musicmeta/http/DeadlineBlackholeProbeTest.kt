package com.landofoz.musicmeta.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The field reproduction, gated by `-Dinclude.probe=true`: a 307 into a SYN-blackholing address,
 * which is how the Cover Art Archive outage that filed the ticket presented. Not part of the
 * gating suite because a CI network may answer TEST-NET-1 with an RST or an ICMP reject — the
 * fast-failing *passing* case — and a probe that cannot hang proves nothing.
 *
 * TEST-NET-1 (`192.0.2.1`) is routed nowhere and answers no RST, so a connect sits in SYN-SENT
 * until the socket's own connect timeout fires. The redirect target keeps the `http` scheme so the
 * follow happens on the code path the ticket's stack trace indicts.
 *
 * Run: `./gradlew :musicmeta-core:test -Dinclude.probe=true --tests "*.DeadlineBlackholeProbeTest"`
 * Baseline evidence (red before the fix, on both clients) and the full metrics tables:
 * `.scratch/enrich-deadline-not-a-bound/prototypes/`, committed on the two prototype branches.
 */
class DeadlineBlackholeProbeTest {

    private lateinit var server: HttpServer

    @Before fun startServer() {
        Assume.assumeTrue(
            "Blackhole probe disabled. Run with -Dinclude.probe=true on a network where " +
                "TEST-NET-1 hangs rather than refuses.",
            System.getProperty("include.probe") == "true",
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://192.0.2.1/blackholed")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
    }

    @After fun stopServer() {
        if (::server.isInitialized) server.stop(0)
    }

    @Test fun `a redirect into a SYN blackhole returns at the deadline, not the connect timeout`() {
        // Given - a client whose own connect timeout dwarfs the deadline
        val client = DefaultHttpClient("musicmeta-probe", timeoutMs = 60_000, maxRetries = 1)

        // When - the fetch follows the 307 into the blackhole under a 2 s deadline
        val startNanos = System.nanoTime()
        val result = runBlocking {
            withContext(EnrichDeadline(budgetMs = 2_000)) {
                client.fetchJsonResult("http://127.0.0.1:${server.address.port}/front")
            }
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the connect gave up near the deadline; before the fix this row measured the full
        // transport timeout (60 s here, 10 s in the prototype tables)
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("took ${wallMs}ms against a 2000ms deadline", wallMs < 6_000)
    }
}
