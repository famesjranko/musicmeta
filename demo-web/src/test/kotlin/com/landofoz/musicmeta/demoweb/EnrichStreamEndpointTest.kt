package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP-level coverage for `/api/enrich-stream`. The endpoint's contract to the page is the event
 * sequence, not any one payload: zero or more `snapshot` events carrying cumulative state, then
 * exactly one `complete` event that is the whole answer. A snapshot the transport dropped is
 * invisible by design (the writer conflates), so every assertion here is about the sequence's
 * shape and about `complete` being both present and total.
 */
class EnrichStreamEndpointTest {

    /** One decoded SSE event: its `event:` name and the concatenated `data:` payload. */
    private data class SseEvent(val name: String, val data: String)

    /**
     * An engine whose stream is a fixed script, so a test states the emission count, the per-type
     * outcomes and the pacing outright instead of hoping a provider stub produces them. It also
     * records what [enrichProgressive] was called with, which is how the query parameters are
     * pinned to the engine call.
     */
    private class ScriptedEngine(
        private val script: List<EnrichmentResults>,
        private val gapMs: Long = 0,
        /** When set, the stream throws instead of emitting this many snapshots in — a failed run, not a failed socket. */
        private val throwAfter: Int? = null,
    ) : EnrichmentEngine {
        val progressiveCalls = ConcurrentLinkedQueue<ProgressiveCall>()

        /** Completed when the stream terminates, carrying why — null for an ordinary end. */
        val termination = CompletableFuture<Throwable?>()

        data class ProgressiveCall(
            val request: EnrichmentRequest,
            val types: Set<EnrichmentType>,
            val forceRefresh: Boolean,
        )

        override val cache: EnrichmentCache = InMemoryEnrichmentCache()

        override suspend fun enrich(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): EnrichmentResults = script.last()

        override fun enrichProgressive(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): Flow<EnrichmentResults> = flow {
            progressiveCalls.add(ProgressiveCall(request, types, forceRefresh))
            script.forEachIndexed { index, snapshot ->
                if (index > 0 && gapMs > 0) delay(gapMs)
                if (index == throwAfter) throw IllegalStateException("provider chain gave up")
                emit(snapshot)
            }
        }.onCompletion { cause -> termination.complete(cause) }

        override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> = emptyList()
        override fun getProviders(): List<ProviderInfo> = emptyList()
        override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) = Unit
        override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = false
        override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = Unit
    }

    /** Records every request it is asked to enrich, so a test can count what reached the providers. */
    private class RecordingGenreProvider : EnrichmentProvider {
        val calls = ConcurrentLinkedQueue<EnrichmentRequest>()
        override val id = "stub-genre"
        override val displayName = "Stub Genre"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            calls.add(request)
            return EnrichmentResult.Success(
                type = EnrichmentType.GENRE,
                data = EnrichmentData.Metadata(genreTags = listOf(GenreTag("Trip Hop", 0.9f, sources = listOf("stub-genre")))),
                provider = "stub-genre",
                confidence = 0.9f,
            )
        }
    }

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun startWith(engine: EnrichmentEngine): Int =
        startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0)

    private fun raw(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /**
     * Reads the whole stream and splits it into events. Blocking on the complete body is safe
     * precisely because the endpoint always terminates: an unterminated stream fails this as a
     * hang, which is the honest signal.
     */
    private fun stream(port: Int, path: String): List<SseEvent> {
        val response = raw(port, path)
        assertEquals(200, response.statusCode())
        return response.body().split("\n\n").mapNotNull { block ->
            val name = block.lineSequence().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ")
            val data = block.lineSequence().filter { it.startsWith("data: ") }
                .joinToString("") { it.removePrefix("data: ") }
            if (name == null) null else SseEvent(name, data)
        }
    }

    private fun snapshotOf(event: SseEvent): StreamSnapshot =
        json.decodeFromString(StreamSnapshot.serializer(), event.data)

    private fun artistResults(
        settled: Map<EnrichmentType, EnrichmentResult>,
        status: CanonicalStatus = CanonicalStatus.RESOLVED,
    ) = EnrichmentResults(
        raw = settled,
        requestedTypes = EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY,
        identity = IdentityResolution(EnrichmentIdentifiers(), status),
    )

    private fun bio(text: String) = EnrichmentResult.Success(
        type = EnrichmentType.ARTIST_BIO,
        data = EnrichmentData.Biography(text = text, source = "stub-bio"),
        provider = "stub-bio",
        confidence = 0.9f,
    )

    private fun similar() = EnrichmentResult.Success(
        type = EnrichmentType.SIMILAR_ARTISTS,
        data = EnrichmentData.SimilarArtists(
            artists = listOf(SimilarArtist(name = "Massive Attack", matchScore = 0.8f)),
        ),
        provider = "stub-similar",
        confidence = 0.8f,
    )

    private val everyArtistType: Map<EnrichmentType, EnrichmentResult>
        get() = (EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY).associateWith { type ->
            EnrichmentResult.NotFound(type, "stub")
        } + mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."), EnrichmentType.SIMILAR_ARTISTS to similar())

    @Test fun `a multi-snapshot stream sends intermediate snapshots and exactly one complete event`() {
        // Given - an engine that settles two types before settling the rest
        val engine = ScriptedEngine(
            listOf(
                artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))),
                artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."), EnrichmentType.SIMILAR_ARTISTS to similar())),
                artistResults(everyArtistType),
            ),
            gapMs = 40,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - at least one snapshot preceded the single terminal event
        assertTrue(events.count { it.name == "snapshot" } >= 1)
        assertEquals(1, events.count { it.name == "complete" })
        assertEquals("complete", events.last().name)
    }

    @Test fun `snapshot sequence numbers increase and each snapshot carries firstPaintMs`() {
        // Given - an engine that settles two types before settling the rest
        val engine = ScriptedEngine(
            listOf(
                artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))),
                artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."), EnrichmentType.SIMILAR_ARTISTS to similar())),
                artistResults(everyArtistType),
            ),
            gapMs = 60,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the snapshots reaching the page carry strictly increasing sequence numbers
        val snapshots = events.filter { it.name == "snapshot" }.map { snapshotOf(it) }
        assertTrue(snapshots.size >= 2)
        snapshots.zipWithNext().forEach { (earlier, later) -> assertTrue(later.sequence > earlier.sequence) }

        // Then - every snapshot reports when the first paint landed, not only the one that made it
        assertTrue(snapshots.all { it.firstPaintMs != null })
    }

    @Test fun `an identity-only snapshot counts as the first paint`() {
        // Given - the engine's first emission carries a settled identity verdict and no settled types
        val engine = ScriptedEngine(
            listOf(
                artistResults(emptyMap(), status = CanonicalStatus.FAILED),
                artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol.")), status = CanonicalStatus.FAILED),
                artistResults(everyArtistType, status = CanonicalStatus.FAILED),
            ),
            gapMs = 60,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the identity-only snapshot reaches the page and stamps the first paint, because
        // the verdict is what a visitor can act on before any enrichment settles
        val snapshots = events.filter { it.name == "snapshot" }.map { snapshotOf(it) }
        assertTrue("expected at least two snapshots, got ${snapshots.size}", snapshots.size >= 2)
        assertTrue(
            "the identity-only snapshot must stamp firstPaintMs: ${snapshots.first()}",
            snapshots.first().firstPaintMs != null,
        )
    }

    @Test fun `the complete event settles every requested type even when snapshots were conflated away`() {
        // Given - an engine that emits its whole script back to back, so the writer conflates
        val engine = ScriptedEngine(
            List(12) { artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))) } +
                artistResults(everyArtistType),
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - however many snapshots survived, the terminal event reports nothing pending
        val complete = snapshotOf(events.single { it.name == "complete" })
        assertTrue(complete.pending.isEmpty())
        assertFalse(complete.identityPending)
    }

    @Test fun `a never-attempted identity with nothing enriched is not a first paint`() {
        // Given - a run whose identity was never attempted, so the first emission carries a
        // NOT_ATTEMPTED verdict and no settled type at all
        val engine = ScriptedEngine(
            listOf(
                artistResults(emptyMap(), status = CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED),
                artistResults(
                    mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol.")),
                    status = CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED,
                ),
                artistResults(everyArtistType, status = CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED),
            ),
            gapMs = 60,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - nothing was painted until a type settled, because a resolution that never ran
        // gives the page nothing to draw
        val snapshots = events.filter { it.name == "snapshot" }.map { snapshotOf(it) }
        assertTrue("expected at least two snapshots, got ${snapshots.size}", snapshots.size >= 2)
        assertNull(
            "an empty snapshot with an unattempted identity must not stamp firstPaintMs: ${snapshots.first()}",
            snapshots.first().firstPaintMs,
        )
        assertNotNull(
            "the first settled type must stamp firstPaintMs: ${snapshots[1]}",
            snapshots[1].firstPaintMs,
        )
    }

    @Test fun `a cache-warm stream that emits once sends no snapshot events, only complete`() {
        // Given - an engine whose stream has a single emission, the shape a fully cached run takes
        val engine = ScriptedEngine(listOf(artistResults(everyArtistType)))
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - nothing intermediate reached the page, so it has no loading state to flash
        assertEquals(0, events.count { it.name == "snapshot" })
        assertEquals(1, events.count { it.name == "complete" })
    }

    @Test fun `an unsettled type is reported as pending on an intermediate snapshot`() {
        // Given - an engine that settles one type, then the rest
        val engine = ScriptedEngine(
            listOf(artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))), artistResults(everyArtistType)),
            gapMs = 60,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the intermediate names the types still coming, and the settled one is not among them
        val intermediate = snapshotOf(events.first { it.name == "snapshot" })
        assertTrue(EnrichmentType.SIMILAR_ARTISTS.name in intermediate.pending)
        assertFalse(EnrichmentType.ARTIST_BIO.name in intermediate.pending)
    }

    @Test fun `refresh=true reaches the engine as forceRefresh`() {
        // Given - an engine that records what its stream was called with
        val engine = ScriptedEngine(listOf(artistResults(everyArtistType)))
        val port = startWith(engine)

        // When - streaming an artist with the refresh parameter set
        stream(port, "/api/enrich-stream?kind=artist&name=Portishead&refresh=true")

        // Then - the engine was asked for a forced refresh, not a cached read
        val call = engine.progressiveCalls.first { (it.request as? EnrichmentRequest.ForArtist)?.name == "Portishead" }
        assertTrue(call.forceRefresh)
    }

    @Test fun `refresh is absent by default`() {
        // Given - an engine that records what its stream was called with
        val engine = ScriptedEngine(listOf(artistResults(everyArtistType)))
        val port = startWith(engine)

        // When - streaming an artist with no refresh parameter
        stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the engine was not asked to bypass its cache
        val call = engine.progressiveCalls.first { (it.request as? EnrichmentRequest.ForArtist)?.name == "Portishead" }
        assertFalse(call.forceRefresh)
    }

    @Test fun `a timed-out type is reported apart from an ordinary failure`() {
        // Given - an engine whose terminal snapshot carries one TIMEOUT and one PARSE error
        val settled = everyArtistType +
            mapOf(
                EnrichmentType.ARTIST_TOP_TRACKS to EnrichmentResult.Error(
                    EnrichmentType.ARTIST_TOP_TRACKS, "stub-slow", "deadline", errorKind = ErrorKind.TIMEOUT,
                ),
                EnrichmentType.BAND_MEMBERS to EnrichmentResult.Error(
                    EnrichmentType.BAND_MEMBERS, "stub-broken", "bad json", errorKind = ErrorKind.PARSE,
                ),
            )
        val engine = ScriptedEngine(listOf(artistResults(settled)))
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the two failures carry distinct error kinds rather than one undifferentiated error
        val providers = snapshotOf(events.single { it.name == "complete" }).response.meta.providers
        assertEquals("TIMEOUT", providers.single { it.type == EnrichmentType.ARTIST_TOP_TRACKS.name }.errorKind)
        assertEquals("PARSE", providers.single { it.type == EnrichmentType.BAND_MEMBERS.name }.errorKind)
    }

    @Test fun `identity is pending on an intermediate snapshot that has not resolved it`() {
        // Given - an engine whose first snapshot is emitted while identity resolution is still running
        val engine = ScriptedEngine(
            listOf(
                artistResults(
                    mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol.")),
                    status = CanonicalStatus.RESOLVING,
                ),
                artistResults(everyArtistType),
            ),
            gapMs = 60,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the page is told the header is provisional, and told when it stops being so
        assertTrue(snapshotOf(events.first { it.name == "snapshot" }).identityPending)
        assertFalse(snapshotOf(events.single { it.name == "complete" }).identityPending)
    }

    @Test fun `a stream against real providers carries the data they returned`() {
        // Given - a real engine over one stub provider that answers GENRE
        val provider = RecordingGenreProvider()
        val engine = EnrichmentEngine.Builder().addProvider(provider).cache(InMemoryEnrichmentCache()).build()
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the terminal event carries the chip that provider supplied, through the same mapper
        val complete = snapshotOf(events.single { it.name == "complete" })
        assertTrue(complete.response.summary.genres.any { chip -> chip.sources.contains("stub-genre") })
    }

    @Test fun `a section whose type has not settled holds its place as pending`() {
        // Given - an engine whose first snapshot has settled nothing that fills a section
        val engine = ScriptedEngine(
            listOf(artistResults(emptyMap()), artistResults(everyArtistType)),
            gapMs = 60,
        )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the similar-artists card is present and empty, so it cannot appear later and reflow
        val intermediate = snapshotOf(events.first { it.name == "snapshot" }).response
        val card = intermediate.sections.single { it.key == "similar_artists" }
        assertTrue(card.pending)
        assertTrue(card.items.isEmpty())
    }

    @Test fun `a settled section is never marked pending`() {
        // Given - an engine whose terminal snapshot has settled every requested type
        val engine = ScriptedEngine(listOf(artistResults(everyArtistType)))
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - nothing on the finished page still claims to be loading
        val complete = snapshotOf(events.single { it.name == "complete" }).response
        assertTrue(complete.sections.none { it.pending })
        assertTrue(complete.summary.pendingSlots.isEmpty())
    }

    @Test fun `an album stream is served, not rejected as an unsupported kind`() {
        // Given - an engine with a scripted album-shaped terminal snapshot
        val engine = ScriptedEngine(
            listOf(
                EnrichmentResults(
                    raw = EnrichmentRequest.DEFAULT_ALBUM_TYPES.associateWith { EnrichmentResult.NotFound(it, "stub") },
                    requestedTypes = EnrichmentRequest.DEFAULT_ALBUM_TYPES,
                    identity = IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.RESOLVED),
                ),
            ),
        )
        val port = startWith(engine)

        // When - streaming an album through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=album&name=Dummy&artist=Portishead")

        // Then - it streams like any other kind rather than falling back to the single-shot path
        assertEquals(1, events.count { it.name == "complete" })
        assertEquals("album", snapshotOf(events.single { it.name == "complete" }).response.kind)
    }

    @Test fun `the stream path is routed to the stream handler, not to the single-shot prefix`() {
        // Given - a scripted engine behind both endpoints
        val engine = ScriptedEngine(listOf(artistResults(everyArtistType)))
        val port = startWith(engine)

        // When - requesting the stream path. `/api/enrich` is a prefix match in this server, so
        // nothing but the response's own media type distinguishes which handler answered.
        val response = raw(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the stream handler answered
        assertEquals(200, response.statusCode())
        assertEquals("text/event-stream; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""))
    }

    @Test fun `a request missing its required fields is rejected before the stream opens`() {
        // Given - a scripted engine that would stream if it were asked to
        val engine = ScriptedEngine(listOf(artistResults(everyArtistType)))
        val port = startWith(engine)

        // When - requesting a track stream with no artist
        val response = raw(port, "/api/enrich-stream?kind=track&name=Glory+Box")

        // Then - the caller gets a JSON error it can read, not a stream that says nothing
        assertEquals(400, response.statusCode())
        assertNotNull(json.decodeFromString(ApiError.serializer(), response.body()).error)
    }

    /** Refuses every write, the way a socket whose reader has gone does, and counts the attempts. */
    private class DeadOutputStream : OutputStream() {
        var attempts = 0
            private set

        override fun write(b: Int) {
            attempts += 1
            throw IOException("broken pipe")
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            attempts += 1
            throw IOException("broken pipe")
        }
    }

    @Test fun `reporting a failure to a client that has left neither throws nor writes again`() {
        // Given - a writer whose one write has already been refused, which is what a reader leaving
        // mid-stream looks like from the server's side
        val out = DeadOutputStream()
        val writer = SseWriter(out)
        val dropped = runCatching { writer.write("snapshot", "{}") }.exceptionOrNull()
        assertTrue(dropped is IOException)
        assertEquals(1, out.attempts)

        // When - the handler reports the run's failure through the same writer
        reportStreamFailure(writer, dropped as Exception)

        // Then - it neither escaped nor retried the report onto a socket that is already gone
        assertEquals(1, out.attempts)
    }

    @Test fun `a payload containing a newline is framed as one data line per line, not one broken event`() {
        // Given - a writer over a plain byte sink, and a payload holding an embedded newline
        val out = ByteArrayOutputStream()
        val writer = SseWriter(out)

        // When - writing that payload as one event
        writer.write("snapshot", "line one\nline two")

        // Then - every line of the payload becomes its own "data: " line, so a reader cannot mistake
        // the second line for the blank line that ends the event
        val frame = out.toString(StandardCharsets.UTF_8.name())
        assertEquals("event: snapshot\ndata: line one\ndata: line two\n\n", frame)
    }

    @Test fun `a client that leaves mid-stream stops the handler and leaves the server serving`() {
        // Given - a long scripted stream, so several writes land after the reader goes
        val engine =
            ScriptedEngine(
                List(30) { artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))) } +
                    artistResults(everyArtistType),
                gapMs = 60,
            )
        val port = startWith(engine)

        // When - the client reads one event and then drops the connection
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write(
                "GET /api/enrich-stream?kind=artist&name=Portishead HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            while (reader.readLine()?.startsWith("data: ") == false) Unit
        }

        // Then - this handler's own stream collection stops rather than writing on to a reader
        // that has gone, and it returns cleanly enough that the server keeps answering other
        // requests. That is all a dropped client buys here: the real engine's cancellation
        // contract is complete-and-cache (see EnrichmentEngine.enrichProgressive), so a live
        // fan-out is never proven cancelled by this — only that this handler stopped watching it.
        assertNotNull(engine.termination.get(20, TimeUnit.SECONDS))
        assertEquals(200, raw(port, "/api/health").statusCode())
    }

    @Test fun `a client that leaves between snapshots is noticed by the heartbeat, not the next snapshot`() {
        // Given - a script whose next snapshot is half a minute away, so nothing but a heartbeat
        // can notice the reader leaving before then, and the admission permit is held meanwhile
        val engine =
            ScriptedEngine(
                listOf(
                    artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))),
                    artistResults(everyArtistType),
                ),
                gapMs = 30_000,
            )
        val port = startWith(engine)

        // When - the client reads the first snapshot and drops the connection
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write(
                "GET /api/enrich-stream?kind=artist&name=Portishead HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            while (reader.readLine()?.startsWith("data: ") == false) Unit
        }

        // Then - the collection stops within the heartbeat's notice, well inside the 30s gap the
        // next snapshot write would have waited out holding a permit
        assertNotNull(engine.termination.get(10, TimeUnit.SECONDS))
        assertEquals(200, raw(port, "/api/health").statusCode())
    }

    @Test fun `a run that fails while the client is still there reports an error event`() {
        // Given - an engine whose stream throws part way through, with nothing wrong with the socket
        val engine =
            ScriptedEngine(
                List(4) { artistResults(mapOf(EnrichmentType.ARTIST_BIO to bio("Bristol."))) },
                gapMs = 30,
                throwAfter = 2,
            )
        val port = startWith(engine)

        // When - streaming an artist through the endpoint
        val events = stream(port, "/api/enrich-stream?kind=artist&name=Portishead")

        // Then - the failure reaches the page as the stream's terminal event, and no complete claims
        // an answer the run never produced
        assertEquals(0, events.count { it.name == "complete" })
        assertEquals("error", events.last().name)
        assertTrue(json.decodeFromString(ApiError.serializer(), events.last().data).error.isNotBlank())
    }
}
