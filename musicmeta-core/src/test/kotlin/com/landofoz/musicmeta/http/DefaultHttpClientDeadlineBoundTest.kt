package com.landofoz.musicmeta.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The enclosing [EnrichDeadline] binds the transport itself, not just the ladder's sleeps:
 * coroutine cancellation cannot interrupt a thread blocked in socket connect or read, so the
 * deadline only holds if each leg's own timeout is clamped to what is left of it — including
 * every leg of a redirect chain, which spends the budget between one hop and the next.
 *
 * Real sockets and real elapsed time throughout: [EnrichDeadline] reads `System.nanoTime()`,
 * which `runTest`'s virtual clock does not move, and a virtual-clock timeout over real I/O fires
 * unconditionally (`docs/pitfalls.md` §17).
 */
class DefaultHttpClientDeadlineBoundTest {

    private lateinit var server: HttpServer

    /** How long each request stalls before answering; the path picks the behaviour. */
    private val requests = AtomicInteger()

    /** Releases every stalled handler at teardown, so a test's stall never outlives it. */
    private val released = CountDownLatch(1)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A pool, not the default single thread: a stalled handler must not delay the next hop's
        // request, or every chain below reads as one long stall whatever the client did.
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/hang") { exchange ->
            requests.incrementAndGet()
            released.await(10, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, -1) }
        }
        server.createContext("/chain") { exchange ->
            requests.incrementAndGet()
            // Each hop stalls well under any single leg's own timeout, then redirects on: no one
            // leg is slow enough to trip an unclamped timeout, only the chain's total is.
            Thread.sleep(600)
            val hop = exchange.requestURI.path.removePrefix("/chain/").toInt()
            runCatching {
                if (hop >= 3) {
                    val body = """{"ok":true}"""
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                } else {
                    exchange.responseHeaders.add("Location", "/chain/${hop + 1}")
                    exchange.sendResponseHeaders(307, -1)
                }
            }
        }
        server.start()
    }

    @After fun stopServer() {
        released.countDown()
        server.stop(0)
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    @Test fun `a leg's read timeout is clamped to what is left of the deadline`() = runTest {
        // Given - a server that never answers, and a client whose own timeout dwarfs the deadline
        val client = DefaultHttpClient("musicmeta-test", timeoutMs = 5_000, maxRetries = 1)

        // When - one fetch runs under a 600 ms deadline, against real elapsed time
        val startNanos = System.nanoTime()
        val result = withContext(EnrichDeadline(budgetMs = 600)) {
            client.fetchJsonResult("${base()}/hang")
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the transport gave up near the deadline, not at its own 5 s timeout
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("took ${wallMs}ms against a 600ms deadline", wallMs < 2_500)
    }

    @Test fun `a redirect chain spends one deadline, not one budget per hop`() = runTest {
        // Given - a 3-hop redirect chain whose hops each stall 600 ms — no single leg slow enough
        // to trip an unclamped 5 s timeout, so only a per-hop reclamp can stop the chain
        val client = DefaultHttpClient("musicmeta-test", timeoutMs = 5_000, maxRetries = 1)

        // When - the fetch runs under an 800 ms deadline, against real elapsed time
        val startNanos = System.nanoTime()
        val result = withContext(EnrichDeadline(budgetMs = 800)) {
            client.fetchJsonResult("${base()}/chain/0")
        }
        val wallMs = (System.nanoTime() - startNanos) / 1_000_000

        // Then - the chain was cut at the deadline instead of walking all four legs to a late Ok
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertTrue("took ${wallMs}ms against an 800ms deadline", wallMs < 2_000)
        assertTrue("expected the chain cut before its end, saw ${requests.get()} hops", requests.get() < 4)
    }

    @Test fun `with no deadline installed the client's own timeout stands`() = runTest {
        // Given - the same never-answering server, no EnrichDeadline in context
        val client = DefaultHttpClient("musicmeta-test", timeoutMs = 300, maxRetries = 1)

        // When - one fetch runs bare, as a consumer driving the client directly does
        val result = client.fetchJsonResult("${base()}/hang")

        // Then - the configured timeout still bounds the call on its own
        assertTrue("expected NetworkError, got $result", result is HttpResult.NetworkError)
        assertEquals(1, requests.get())
    }
}
