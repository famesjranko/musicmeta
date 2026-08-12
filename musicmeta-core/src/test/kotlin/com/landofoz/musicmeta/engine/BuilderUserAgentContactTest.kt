package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MusicBrainz and Wikimedia require contact information in the User-Agent. The builder's
 * `contact()` is the short way to supply it, and a build whose *wire* carries none says so once —
 * which is not the same question as what the composed config says.
 */
class BuilderUserAgentContactTest {

    /** Records warnings so a test can assert on the ones the builder emits. */
    private class RecordingLogger : EnrichmentLogger {
        val warnings = mutableListOf<String>()
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            warnings.add(message)
        }
    }

    private fun provider(id: String) = FakeProvider(
        id = id,
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
    )

    private fun configOf(engine: EnrichmentEngine) = (engine as DefaultEnrichmentEngine).config

    /** The User-Agent header a [DefaultHttpClient] built with [userAgent] puts on a real request. */
    private suspend fun userAgentOnTheWire(userAgent: String): List<String?> {
        val received = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received.add(exchange.requestHeaders.getFirst("User-Agent"))
            val body = "{}"
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            DefaultHttpClient(userAgent, maxRetries = 1)
                .fetchJsonResult("http://127.0.0.1:${server.address.port}/artist")
        } finally {
            server.stop(0)
        }
        return received.toList()
    }

    @Test fun `contact info composes the policy-suggested User-Agent, on the wire`() = runTest {
        // Given - an engine built from contact information and no User-Agent of its own
        val engine = EnrichmentEngine.Builder().contact("https://example.com/musicmeta").build()

        // When - a client built from that engine's config makes a request
        val received = userAgentOnTheWire(configOf(engine).userAgent)

        // Then - the header that arrived is the form both policies ask for
        assertEquals(
            listOf("MusicEnrichmentEngine/1.0 ( https://example.com/musicmeta )"),
            received,
        )
    }

    @Test fun `a custom User-Agent is used verbatim on the wire and contact is ignored`() = runTest {
        // Given - an engine built from both a full custom User-Agent and contact information
        val engine = EnrichmentEngine.Builder()
            .config(EnrichmentConfig(userAgent = "MyApp/2.0 (me@example.com)"))
            .contact("https://example.com/ignored")
            .build()

        // When - a client built from that engine's config makes a request
        val received = userAgentOnTheWire(configOf(engine).userAgent)

        // Then - the caller's string arrives untouched
        assertEquals(listOf("MyApp/2.0 (me@example.com)"), received)
    }

    @Test fun `the contactless default warns once, naming the fix`() {
        // Given - a builder with MusicBrainz registered and no contact information
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("musicbrainz"))
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - exactly one warning names the unmet policy and the builder field that fixes it
        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings.single()
        assertTrue(warning, "musicbrainz" in warning)
        assertTrue(warning, "contact information" in warning)
        assertTrue(warning, "EnrichmentEngine.Builder.contact()" in warning)
    }

    @Test fun `the warning names Wikidata when Wikidata is what is registered`() {
        // Given - a builder with only Wikidata registered and no contact information
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("wikidata"))
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - the one warning names the provider whose policy is unmet
        assertTrue(logger.warnings.single(), "wikidata" in logger.warnings.single())
    }

    @Test fun `contact information silences the warning`() {
        // Given - the same provider set, with contact information supplied
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("musicbrainz"))
            .addProvider(provider("wikipedia"))
            .contact("me@example.com")
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - nothing is warned about
        assertEquals(emptyList<String>(), logger.warnings)
    }

    @Test fun `a provider set with no affected provider does not warn`() {
        // Given - the contactless default, but no MusicBrainz or Wikimedia provider registered
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("deezer"))
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - no policy demands contact information, so nothing is warned about
        assertEquals(emptyList<String>(), logger.warnings)
    }

    @Test fun `contact after withDefaultProviders warns that the wire missed it`() {
        // Given - a builder whose default providers were built before contact() was called
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .logger(logger)
            .withDefaultProviders()
            .contact("https://example.com/too-late")

        // When - building the engine
        builder.build()

        // Then - one warning says the wire kept the contactless default, and names the ordering
        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings.single()
        assertTrue(warning, EnrichmentConfig.DEFAULT_USER_AGENT in warning)
        assertTrue(warning, "contact() before withDefaultProviders()" in warning)
    }

    @Test fun `contact before withDefaultProviders reaches the client and does not warn`() {
        // Given - a builder given contact information ahead of its default providers
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .logger(logger)
            .contact("https://example.com/in-time")
            .withDefaultProviders()

        // When - building the engine
        val engine = builder.build()

        // Then - the composed User-Agent is what the client was built with, so nothing is warned
        assertEquals(
            "MusicEnrichmentEngine/1.0 ( https://example.com/in-time )",
            configOf(engine).userAgent,
        )
        assertEquals(emptyList<String>(), logger.warnings)
    }

    @Test fun `contact alongside a caller-supplied client warns that it cannot reach it`() {
        // Given - a builder given both its own HttpClient and contact information
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("musicbrainz"))
            .httpClient(FakeHttpClient())
            .contact("https://example.com/unreachable")
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - one warning says contact() cannot alter that client, and where to set it instead
        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings.single()
        assertTrue(warning, "https://example.com/unreachable" in warning)
        assertTrue(warning, "httpClient()" in warning)
        assertTrue(warning, "DefaultHttpClient" in warning)
    }

    @Test fun `a caller-supplied client carrying its own User-Agent is not warned about`() {
        // Given - a builder whose client sets a compliant User-Agent itself, so contact() is unused
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("musicbrainz"))
            .httpClient(FakeHttpClient())
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - the wire is the client's to describe, so the contactless default is not assumed
        assertEquals(emptyList<String>(), logger.warnings)
    }

    @Test fun `contact rejects a line break`() {
        // Given - a contact string carrying a header-splitting newline
        val builder = EnrichmentEngine.Builder()

        // When - it is offered to contact()
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            builder.contact("me@example.com\r\nX-Injected: 1")
        }

        // Then - it is refused at the call, naming the character, not once per request
        val message = thrown.message!!
        assertTrue(message, "carriage return" in message)
    }

    @Test fun `contact rejects a parenthesis`() {
        // Given - a contact string carrying the character that closes the User-Agent comment
        val builder = EnrichmentEngine.Builder()

        // When - it is offered to contact()
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            builder.contact("MyApp (me@example.com)")
        }

        // Then - it is refused, naming the offending character
        val message = thrown.message!!
        assertTrue(message, "'('" in message)
    }

    @Test fun `userAgentWithContact rejects a line break`() {
        // Given - the same newline, offered to the composition function directly

        // When - a User-Agent is composed from it
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            EnrichmentConfig.userAgentWithContact("me@example.com\nX-Injected: 1")
        }

        // Then - direct-wiring callers get the same refusal the builder gives
        val message = thrown.message!!
        assertTrue(message, "line feed" in message)
    }

    @Test fun `userAgentWithContact rejects a parenthesis`() {
        // Given - a parenthesis, offered to the composition function directly

        // When - a User-Agent is composed from it
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            EnrichmentConfig.userAgentWithContact("me@example.com)")
        }

        // Then - direct-wiring callers get the same refusal the builder gives
        val message = thrown.message!!
        assertTrue(message, "')'" in message)
    }
}
