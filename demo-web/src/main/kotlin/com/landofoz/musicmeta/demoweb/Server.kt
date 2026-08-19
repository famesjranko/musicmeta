package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.KeyRequirement
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.ProviderCatalog
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.ProviderPolicies
import com.landofoz.musicmeta.TrackPreviewRequest
import com.landofoz.musicmeta.TrackProfile
import com.landofoz.musicmeta.albumProfile
import com.landofoz.musicmeta.artistProfile
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.discoverMbidEntityType
import com.landofoz.musicmeta.resolveTrackPreviews
import com.landofoz.musicmeta.trackProfile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private val json = Json { encodeDefaults = true }

/** Anchor class purely so [Class.getResourceAsStream] has a classloader to resolve against. */
private class ResourceAnchor

private const val MAX_IDS_PARAM_LENGTH = 512
private const val MAX_IDENTIFIER_VALUE_LENGTH = 100

/** Thrown by [decodeTrackIdentifiers] for anything that must reach the caller as an HTTP 400. */
internal class InvalidIdentifiers(message: String) : Exception(message)

/**
 * Decodes a [SectionItem.identifiers]/[EnrichTarget.identifiers] `ids` query parameter into
 * [EnrichmentIdentifiers] for a track-scoped request, or null when [raw] is absent — a name-only
 * lookup. Malformed JSON (including a field [WireIdentifiers] does not
 * declare) or an oversized parameter throws [InvalidIdentifiers] before reaching
 * [validateTrackIdentifiers], which owns every other rejection reason — the caller turns either
 * into a 400, never a silent name-only downgrade.
 */
internal fun decodeTrackIdentifiers(raw: String?): EnrichmentIdentifiers? {
    if (raw.isNullOrBlank()) return null
    if (raw.length > MAX_IDS_PARAM_LENGTH) throw InvalidIdentifiers("ids exceeds the size limit")
    val wire = try {
        json.decodeFromString<WireIdentifiers>(raw)
    } catch (e: Exception) {
        throw InvalidIdentifiers("ids is malformed: ${e.message}")
    }
    return validateTrackIdentifiers(wire)
}

/**
 * Validates an already-deserialized [WireIdentifiers] for a track-scoped request — the one place
 * [InvalidateRequest.identifiers] and [decodeTrackIdentifiers]'s query-parameter path both convert
 * to [EnrichmentIdentifiers], so a request body and an `ids` query parameter are held to the same
 * scope and size rules. A non-`TRACK` [WireIdentifiers.entityKind] or an oversized value throws
 * [InvalidIdentifiers]; [WireIdentifiers.deezerTrackId] is the only trusted identifier besides
 * [WireIdentifiers.musicBrainzId] this build threads for a track row, and its field only exists
 * under this track-scoped envelope — a namespace this build does not otherwise trust for the
 * track it arrived on has no field to carry it in, so there is no allowlist left to maintain here.
 */
internal fun validateTrackIdentifiers(wire: WireIdentifiers): EnrichmentIdentifiers {
    if (wire.entityKind != WireEntityKind.TRACK) {
        throw InvalidIdentifiers("ids entityKind ${wire.entityKind} does not match this track request")
    }
    val mbid = wire.musicBrainzId?.also {
        if (it.isBlank() || it.length > MAX_IDENTIFIER_VALUE_LENGTH) {
            throw InvalidIdentifiers("musicBrainzId is invalid")
        }
    }
    val deezerTrackId = wire.deezerTrackId?.also {
        if (it.isBlank() || it.length > MAX_IDENTIFIER_VALUE_LENGTH) {
            throw InvalidIdentifiers("deezerTrackId value is invalid")
        }
    }
    var identifiers = EnrichmentIdentifiers(musicBrainzId = mbid)
    if (deezerTrackId != null) identifiers = identifiers.with(IdentifierNamespace.DEEZER, deezerTrackId)
    return identifiers
}

/**
 * `WARMING` until one real enrichment round-trip has completed — the JVM's JIT warmup plus each
 * provider's first-ever DNS resolution and TLS handshake, which is what pushes a genuinely cold
 * first request close to (or past) [com.landofoz.musicmeta.EnrichmentConfig.enrichTimeoutMs]. Every
 * request after that reuses warm connections and comfortably finishes inside the timeout.
 *
 * `READY` and `DEGRADED` both mean the round-trip happened; they differ in whether the probe came
 * back with anything other than [EnrichmentResult.Error] for every requested type. `DEGRADED`
 * still serves requests — the process itself is up — but an uptime probe should not flap on a
 * provider outage the way it must not consider the app up before warm-up.
 */
internal enum class HealthState { WARMING, READY, DEGRADED }

private val healthState = AtomicReference(HealthState.WARMING)

@Serializable
internal data class HealthResponse(val ready: Boolean, val status: String)

/**
 * Pure classification of a completed (or failed) warm-up round-trip, kept apart from [warmUp] so
 * it is testable without a thread or a live engine.
 *
 * `READY` claims only that at least one provider answered — a `NotFound` or `RateLimited` counts,
 * not just `Success`, because reachability is what the round-trip is for. It is not a claim that
 * every configured provider works; a specific provider's misconfiguration is the providers panel's
 * job (see the key-missing rows [buildProviderRows] adds), not health's.
 *
 * A `null` [result] with `threw == false` is reachable: [warmUp] only catches `Exception`, so a
 * `Throwable` that isn't one (an `Error` such as `OutOfMemoryError`) leaves `threw = false` and
 * `result` unset while still running the `finally` that calls this. Classifying that as
 * [HealthState.DEGRADED] rather than [HealthState.READY] is what keeps the state from sticking at
 * `WARMING` forever in that case, on the same "unproven, don't claim it" grounds as the throwing
 * case: nothing here establishes that a provider actually answered.
 */
internal fun classifyWarmUp(result: EnrichmentResults?, threw: Boolean): HealthState = when {
    threw || result == null -> HealthState.DEGRADED
    result.raw.values.any { it !is EnrichmentResult.Error } -> HealthState.READY
    else -> HealthState.DEGRADED
}

/**
 * Pure HTTP mapping for a [HealthState], kept apart from the `/api/health` handler so the
 * 503-only-while-`WARMING` contract is testable without standing up a server.
 */
internal fun healthResponseFor(state: HealthState): Pair<Int, HealthResponse> {
    val httpStatus = if (state == HealthState.WARMING) 503 else 200
    return httpStatus to HealthResponse(ready = state != HealthState.WARMING, status = state.name)
}

/**
 * [engineRef] is read fresh per request rather than captured once, so a cache-mode swap takes
 * effect on the very next call. [rebuildEngine] must build every engine over the same shared
 * cache instance — that's what lets STALE_IF_ERROR serve entries a NETWORK_FIRST run warmed
 * before the swap; see [handleConfig].
 */
fun startServer(
    engineRef: AtomicReference<EnrichmentEngine>,
    cacheModeRef: AtomicReference<CacheMode>,
    rebuildEngine: (CacheMode) -> EnrichmentEngine,
    apiKeys: ApiKeyConfig,
    port: Int,
) {
    val indexHtml = ResourceAnchor::class.java.getResourceAsStream("/index.html")?.readBytes()
        ?: error("index.html missing from demo-web resources")
    val indexCss = ResourceAnchor::class.java.getResourceAsStream("/index.css")?.readBytes()
        ?: error("index.css missing from demo-web resources")
    val indexJs = ResourceAnchor::class.java.getResourceAsStream("/index.js")?.readBytes()
        ?: error("index.js missing from demo-web resources")

    val server = HttpServer.create(InetSocketAddress(port), 0)
    // A streaming request holds its thread for the whole enrichment, not for one round trip, so the
    // pool has to cover concurrent readers *and* leave room for the health probe and the provider
    // panel. 16 is headroom for a demo, not a measured capacity.
    server.executor = Executors.newFixedThreadPool(16)

    server.createContext("/") { exchange ->
        when (exchange.requestURI.path) {
            "/" -> exchange.respond(200, "text/html; charset=utf-8", indexHtml)
            "/index.css" -> exchange.respond(200, "text/css; charset=utf-8", indexCss)
            "/index.js" -> exchange.respond(200, "text/javascript; charset=utf-8", indexJs)
            else -> exchange.respond(404, "text/plain", "not found".toByteArray())
        }
    }

    server.createContext("/api/enrich") { exchange -> handleEnrich(exchange, engineRef.get()) }
    // Longest matching prefix wins in this server, so this context takes the path despite
    // `/api/enrich` also matching it.
    server.createContext("/api/enrich-stream") { exchange -> handleEnrichStream(exchange, engineRef.get()) }
    server.createContext("/api/invalidate") { exchange -> handleInvalidate(exchange, engineRef.get()) }
    server.createContext("/api/search") { exchange -> handleSearch(exchange, engineRef.get()) }
    server.createContext("/api/preview") { exchange -> handlePreview(exchange, engineRef.get()) }
    server.createContext("/api/providers") { exchange -> handleProviders(exchange, engineRef.get(), apiKeys) }
    server.createContext("/api/config") { exchange -> handleConfig(exchange, engineRef, cacheModeRef, rebuildEngine) }
    server.createContext("/api/health") { exchange ->
        // WARMING is the one state a hosting platform must not see as up — HTTP 503 keeps it out
        // of rotation until the round-trip completes. READY and DEGRADED both report 200: the
        // process is accepting requests either way, so an uptime probe should not flap on a
        // provider outage. See [healthResponseFor].
        val (httpStatus, body) = healthResponseFor(healthState.get())
        exchange.respondJson(httpStatus, body)
    }

    server.start()
    warmUp(engineRef.get())
}

/**
 * Fires a single throwaway enrichment off its own thread at startup, outside any request's timeout
 * budget, purely to pay the cold-start cost up front. The round-trip's outcome now feeds
 * [classifyWarmUp] to pick [healthState] — [HealthState.READY] or [HealthState.DEGRADED] — so it is
 * captured rather than discarded, but nothing here retries or escalates it: the JIT/DNS/TLS cost is
 * paid either way, and a broken provider is reported, not fixed, by this thread.
 *
 * The blanket `catch (_: Exception)` here skips the `docs/pitfalls.md` §2 `ensureActive()` guard
 * safely: `runBlocking` on this daemon thread starts its own root job, so there is no caller
 * cancellation to honor and nothing this swallow can defeat.
 */
private fun warmUp(engine: EnrichmentEngine) {
    thread(name = "warmup", isDaemon = true) {
        var result: EnrichmentResults? = null
        var threw = false
        try {
            result = runBlocking { engine.artistProfile("Radiohead") }.results
        } catch (_: Exception) {
            threw = true
        } finally {
            healthState.set(classifyWarmUp(result, threw))
        }
    }
}

/**
 * The lookup behind either enrichment endpoint, validated in one place so the streaming and the
 * single-shot path cannot drift on what they accept or on what they say when they refuse.
 */
private data class EnrichQuery(
    val kind: String,
    val name: String,
    val artist: String,
    val album: String?,
    /**
     * An artist, release or recording MBID depending on [kind] — `EnrichmentIdentifiers.musicBrainzId`
     * is polymorphic that way. No form control sends this; it is reachable over the API only,
     * because a name-only request cannot exercise what a caller's identifier does in either
     * direction: an MBID MusicBrainz holds pins the exact entity, while one it does not hold
     * resolves by name regardless.
     */
    val mbid: String?,
    val identifiers: EnrichmentIdentifiers?,
    val forceRefresh: Boolean,
)

/** Thrown by [parseEnrichQuery] for anything that must reach the caller as an HTTP 400. */
private class InvalidEnrichQuery(message: String) : Exception(message)

private fun parseEnrichQuery(rawQuery: String?): EnrichQuery {
    val params = parseQuery(rawQuery)
    val kind = params["kind"]
    val name = params["name"]?.trim().orEmpty()
    val artist = params["artist"]?.trim().orEmpty()
    val valid = kind in setOf("artist", "album", "track", "mbid") &&
        name.isNotBlank() &&
        (kind == "artist" || kind == "mbid" || artist.isNotBlank())
    if (kind == null || !valid) {
        throw InvalidEnrichQuery("kind must be artist|album|track|mbid; name (and artist, for album/track) required")
    }
    val identifiers = try {
        decodeTrackIdentifiers(params["ids"])
    } catch (e: InvalidIdentifiers) {
        throw InvalidEnrichQuery(e.message ?: "invalid ids")
    }
    if (identifiers != null && kind != "track") throw InvalidEnrichQuery("ids is only valid for kind=track")
    return EnrichQuery(
        kind = kind,
        name = name,
        artist = artist,
        album = params["album"]?.trim()?.ifBlank { null },
        mbid = params["mbid"]?.trim()?.ifBlank { null },
        identifiers = identifiers,
        forceRefresh = params["refresh"] == "true",
    )
}

private fun handleEnrich(exchange: HttpExchange, engine: EnrichmentEngine) {
    if (exchange.requestMethod != "GET") {
        exchange.respondJson(405, ApiError("GET required"))
        return
    }
    val query = try {
        parseEnrichQuery(exchange.requestURI.rawQuery)
    } catch (e: InvalidEnrichQuery) {
        exchange.respondJson(400, ApiError(e.message ?: "invalid request"))
        return
    }
    try {
        val (kind, name, artist, album, mbid, identifiers, forceRefresh) = query

        val started = System.currentTimeMillis()
        val response = runBlocking {
            when (kind) {
                // The type is an answer, not a question the user should have to answer first: one
                // identifier goes in, and whichever page it turns out to name comes back. A blank
                // name is what tells identity resolution to fill it from the entity it resolves.
                "mbid" -> when (val entity = engine.discoverMbidEntityType(name)) {
                    MusicBrainzEntityType.RECORDING -> {
                        val results = engine.enrich(
                            EnrichmentRequest.forTrackByMbid(name),
                            EnrichmentRequest.DEFAULT_TRACK_TYPES,
                            forceRefresh,
                        )
                        TrackProfile(
                            results.identity?.title ?: headerFor(entity, name),
                            results.identity?.artist.orEmpty(),
                            results,
                        ).toDemoResponse(System.currentTimeMillis() - started)
                    }
                    MusicBrainzEntityType.RELEASE -> {
                        val results = engine.enrich(
                            EnrichmentRequest.forAlbumByMbid(name),
                            EnrichmentRequest.DEFAULT_ALBUM_TYPES,
                            forceRefresh,
                        )
                        AlbumProfile(
                            results.identity?.title ?: headerFor(entity, name),
                            results.identity?.artist.orEmpty(),
                            results,
                        ).toDemoResponse(System.currentTimeMillis() - started)
                    }
                    MusicBrainzEntityType.ARTIST -> {
                        val results = engine.enrich(
                            EnrichmentRequest.forArtistByMbid(name),
                            EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY,
                            forceRefresh,
                        )
                        // An artist's own name arrives as the title, as it does on a SearchCandidate.
                        ArtistProfile(
                            results.identity?.title ?: headerFor(entity, name),
                            results,
                        ).toDemoResponse(System.currentTimeMillis() - started)
                    }
                    null -> null
                }
                "artist" -> {
                    val profile = engine.artistProfile(
                        name,
                        mbid,
                        types = EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY,
                        forceRefresh = forceRefresh,
                    )
                    val retried = profile.results.retryTransientFailures(
                        engine,
                        EnrichmentRequest.forArtist(name, mbid),
                    )
                    profile.copy(results = retried).toDemoResponse(System.currentTimeMillis() - started)
                }
                "album" ->
                    coroutineScope {
                        val profileDeferred = async {
                            engine.albumProfile(name, artist, mbid, forceRefresh = forceRefresh)
                        }
                        val radioDeferred = async { fetchArtistRadioSection(engine, artist) }
                        val profile = profileDeferred.await()
                        val retried = profile.results.retryTransientFailures(
                            engine,
                            EnrichmentRequest.forAlbum(name, artist, mbid),
                        )
                        val radio = radioDeferred.await()
                        profile.copy(results = retried).toDemoResponse(System.currentTimeMillis() - started, radio)
                    }
                else ->
                    coroutineScope {
                        val profileDeferred = async {
                            engine.trackProfile(
                                name, artist, album, mbid,
                                identifiers = identifiers, forceRefresh = forceRefresh,
                            )
                        }
                        val radioDeferred = async { fetchArtistRadioSection(engine, artist) }
                        val profile = profileDeferred.await()
                        val retried = profile.results.retryTransientFailures(
                            engine,
                            EnrichmentRequest.forTrack(name, artist, album, mbid = mbid, identifiers = identifiers),
                        )
                        val radio = radioDeferred.await()
                        profile.copy(results = retried)
                            .toDemoResponse(System.currentTimeMillis() - started, radio, album)
                    }
            }
        }
        if (response == null) {
            // An identifier MusicBrainz holds under no entity type. Not an error, and not an
            // enrichment either — there is no page to render.
            exchange.respondJson(404, ApiError("MusicBrainz holds no recording, release or artist under that MBID"))
            return
        }
        exchange.respondJson(200, response)
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

/**
 * Identity statuses a mid-stream snapshot cannot read as a verdict. The `NOT_ATTEMPTED_*` family
 * says resolution did not run, which before the terminal snapshot is indistinguishable from
 * "has not run yet" — so a snapshot carrying one presents its header as provisional rather than
 * treating the status as an answer.
 */
private val UNSETTLED_IDENTITY_STATUSES = setOf(
    CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT,
    CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED,
    CanonicalStatus.NOT_ATTEMPTED_NO_PROVIDER,
    CanonicalStatus.NOT_ATTEMPTED_DISABLED,
)

/**
 * The SSE sibling of [handleEnrich], over [EnrichmentEngine.enrichProgressive]: the page paints
 * each type as it settles instead of waiting for the slowest provider. Both endpoints share
 * [parseEnrichQuery] and the same profile mappers, so they can differ in *when* a result arrives
 * and never in what it says.
 *
 * The event name is the contract. `snapshot` is cumulative and provisional; `complete` is sent
 * exactly once and carries the whole answer; `error` replaces `complete` when the run could not
 * finish. A client closes the stream on either terminal event — `EventSource` would otherwise
 * reconnect on the close and re-run the entire enrichment.
 *
 * The writer conflates: a snapshot the client is too slow to receive is dropped rather than
 * queued, because serialising and flushing must never reach back into provider fan-out, and a
 * consumer of cumulative snapshots only ever needs the newest.
 */
private fun handleEnrichStream(exchange: HttpExchange, engine: EnrichmentEngine) {
    if (exchange.requestMethod != "GET") {
        exchange.respondJson(405, ApiError("GET required"))
        return
    }
    val query = try {
        parseEnrichQuery(exchange.requestURI.rawQuery)
    } catch (e: InvalidEnrichQuery) {
        exchange.respondJson(400, ApiError(e.message ?: "invalid request"))
        return
    }

    val started = System.currentTimeMillis()
    exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
    exchange.responseHeaders.add("Cache-Control", "no-cache")
    // A proxy that buffers the response holds every snapshot until the last one, which is the whole
    // benefit gone; this is the header nginx and its imitators read to stop.
    exchange.responseHeaders.add("X-Accel-Buffering", "no")
    // Length 0 selects chunked transfer encoding — there is no total length to declare for a stream
    // whose event count is not known up front.
    exchange.sendResponseHeaders(200, 0)
    val writer = SseWriter(exchange.responseBody)

    try {
        runBlocking { streamEnrichment(engine, query, started, writer) }
    } catch (e: Exception) {
        // A snapshot may already be on the wire, so this cannot become an error status the way
        // handleEnrich's catch does — the status line is long gone. `runBlocking` surfaces a
        // cancellation from inside it as a plain throw on this thread, so there is no ambient job
        // here for `ensureActive()` to consult.
        writer.write("error", json.encodeToString(ApiError(e.message ?: e.javaClass.simpleName)))
    } finally {
        writer.close()
    }
}

/**
 * Frames server-sent events onto one response body, flushing each so nothing waits on a buffer
 * filling. Every write happens on the request thread — the collector below runs on `runBlocking`'s
 * own event loop — so this holds no lock.
 */
private class SseWriter(private val out: OutputStream) {

    /** A write to a socket the client has already dropped throws; the caller treats that as the end. */
    fun write(event: String, data: String) {
        // A newline inside the payload would otherwise be read as a second data line, or worse as
        // the blank line that ends the event. JSON emits none, but framing must not rest on that.
        val body = data.lineSequence().joinToString("\n") { "data: $it" }
        out.write("event: $event\n$body\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun close() {
        try {
            out.close()
        } catch (_: IOException) {
            // The client dropped first; there is nothing left to close cleanly.
        }
    }
}

/**
 * What one streamed lookup needs. [render] runs per snapshot rather than once at the end, which is
 * what lets a header built from [EnrichmentResults.identity] refine on the next event as soon as
 * resolution lands.
 */
private class StreamPlan(
    val request: EnrichmentRequest,
    val types: Set<EnrichmentType>,
    /** The artist whose radio section is fetched alongside the stream, or null for a kind that has none. */
    val radioArtist: String?,
    val render: (EnrichmentResults, Set<EnrichmentType>, Long, Section?) -> DemoResponse,
)

/** null only for an identifier MusicBrainz holds under no entity type — there is no page to stream. */
private suspend fun planFor(engine: EnrichmentEngine, query: EnrichQuery): StreamPlan? = when (query.kind) {
    "artist" -> artistPlan(EnrichmentRequest.forArtist(query.name, query.mbid)) { query.name }
    "album" -> StreamPlan(
        EnrichmentRequest.forAlbum(query.name, query.artist, query.mbid),
        EnrichmentRequest.DEFAULT_ALBUM_TYPES,
        radioArtist = query.artist,
    ) { results, pending, elapsed, radio ->
        AlbumProfile(query.name, query.artist, results).toDemoResponse(elapsed, radio, pending)
    }
    "track" -> StreamPlan(
        EnrichmentRequest.forTrack(
            query.name, query.artist, query.album,
            mbid = query.mbid, identifiers = query.identifiers,
        ),
        EnrichmentRequest.DEFAULT_TRACK_TYPES,
        radioArtist = query.artist,
    ) { results, pending, elapsed, radio ->
        TrackProfile(query.name, query.artist, results).toDemoResponse(elapsed, radio, query.album, pending)
    }
    // The type is an answer, not a question the user should have to answer first — as in
    // [handleEnrich], one identifier goes in and whichever page it names comes back.
    else -> when (val entity = engine.discoverMbidEntityType(query.name)) {
        MusicBrainzEntityType.ARTIST ->
            artistPlan(EnrichmentRequest.forArtistByMbid(query.name)) { results ->
                // An artist's own name arrives as the title, as it does on a SearchCandidate.
                results.identity.title ?: headerFor(entity, query.name)
            }
        MusicBrainzEntityType.RELEASE -> StreamPlan(
            EnrichmentRequest.forAlbumByMbid(query.name),
            EnrichmentRequest.DEFAULT_ALBUM_TYPES,
            radioArtist = null,
        ) { results, pending, elapsed, radio ->
            AlbumProfile(
                results.identity.title ?: headerFor(entity, query.name),
                results.identity.artist.orEmpty(),
                results,
            ).toDemoResponse(elapsed, radio, pending)
        }
        MusicBrainzEntityType.RECORDING -> StreamPlan(
            EnrichmentRequest.forTrackByMbid(query.name),
            EnrichmentRequest.DEFAULT_TRACK_TYPES,
            radioArtist = null,
        ) { results, pending, elapsed, radio ->
            TrackProfile(
                results.identity.title ?: headerFor(entity, query.name),
                results.identity.artist.orEmpty(),
                results,
            ).toDemoResponse(elapsed, radio, query.album, pending)
        }
        null -> null
    }
}

private fun artistPlan(request: EnrichmentRequest, title: (EnrichmentResults) -> String) = StreamPlan(
    request,
    EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY,
    radioArtist = null,
) { results, pending, elapsed, _ ->
    ArtistProfile(title(results), results).toDemoResponse(elapsed, pending)
}

/**
 * Writes the event sequence for one lookup: a `snapshot` per surviving intermediate, then exactly
 * one `complete`.
 *
 * A snapshot that already derives as complete is *not* sent as one. The engine settles its last
 * type before catalog filtering, provenance stamping and the cache write-back have run, so an
 * emission can read as finished while carrying values the finished result does not have. Only the
 * write after collection — past the retry [handleEnrich] also does — is the answer.
 */
private suspend fun streamEnrichment(
    engine: EnrichmentEngine,
    query: EnrichQuery,
    started: Long,
    writer: SseWriter,
) = coroutineScope {
    val plan = planFor(engine, query) ?: run {
        writer.write(
            "error",
            json.encodeToString(ApiError("MusicBrainz holds no recording, release or artist under that MBID")),
        )
        return@coroutineScope
    }

    // The artist-radio section is fetched outside EnrichmentType, so it is not in the stream. It
    // runs alongside and folds into whichever snapshot is being written when it lands.
    val radio = AtomicReference<Section?>(null)
    val radioJob = plan.radioArtist
        ?.takeIf { it.isNotBlank() }
        ?.let { artist -> launch { radio.set(fetchArtistRadioSection(engine, artist)) } }

    var sequence = 0
    var firstPaintMs: Long? = null
    var latest: EnrichmentResults? = null

    engine.enrichProgressive(plan.request, plan.types, query.forceRefresh)
        .conflate()
        .collect { snapshot ->
            latest = snapshot
            val pending = plan.types - snapshot.raw.keys
            if (pending.isEmpty()) return@collect
            if (firstPaintMs == null && snapshot.raw.isNotEmpty()) firstPaintMs = System.currentTimeMillis() - started
            sequence += 1
            writer.write(
                "snapshot",
                json.encodeToString(
                    StreamSnapshot(
                        sequence = sequence,
                        pending = pending.map { it.name }.sorted(),
                        identityPending = snapshot.identity.status in UNSETTLED_IDENTITY_STATUSES,
                        firstPaintMs = firstPaintMs,
                        response = plan.render(snapshot, pending, System.currentTimeMillis() - started, radio.get()),
                    ),
                ),
            )
        }

    val terminal = latest ?: error("the enrichment stream ended without emitting anything")
    radioJob?.join()
    val settled = terminal.retryTransientFailures(engine, plan.request)
    val elapsed = System.currentTimeMillis() - started
    writer.write(
        "complete",
        json.encodeToString(
            StreamSnapshot(
                sequence = sequence + 1,
                pending = (plan.types - settled.raw.keys).map { it.name }.sorted(),
                // Not derived: on the terminal snapshot resolution has run, so whatever status it
                // carries is the verdict — including a NOT_ATTEMPTED_* one, which is an answer here
                // and only ambiguous mid-stream.
                identityPending = false,
                firstPaintMs = firstPaintMs ?: elapsed,
                response = plan.render(settled, emptySet(), elapsed, radio.get()),
            ),
        ),
    )
}

/**
 * `GET` reports the running [CacheMode]; `POST` rebuilds the engine over it. The rebuilt engine is
 * handed the same shared cache every other build used — see [startServer] — so cached entries
 * survive the swap and a STALE_IF_ERROR toggle has something to serve as a fallback.
 */
private fun handleConfig(
    exchange: HttpExchange,
    engineRef: AtomicReference<EnrichmentEngine>,
    cacheModeRef: AtomicReference<CacheMode>,
    rebuildEngine: (CacheMode) -> EnrichmentEngine,
) {
    when (exchange.requestMethod) {
        "GET" -> exchange.respondJson(200, ConfigResponse(cacheModeRef.get().name))
        "POST" -> try {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = try {
                json.decodeFromString<ConfigRequest>(body)
            } catch (_: Exception) {
                exchange.respondJson(400, ApiError("malformed JSON body"))
                return
            }
            val mode = CacheMode.entries.find { it.name == request.cacheMode }
            if (mode == null) {
                exchange.respondJson(400, ApiError("cacheMode must be one of ${CacheMode.entries.map { it.name }}"))
                return
            }
            if (mode == cacheModeRef.get()) {
                exchange.respondJson(200, ConfigResponse(mode.name))
                return
            }
            // cacheModeRef first: the window this leaves is a GET briefly reporting the new mode
            // while engineRef still serves the old one — never the reverse, where a GET would report
            // a mode the running engine has already left behind.
            cacheModeRef.set(mode)
            engineRef.set(rebuildEngine(mode))
            exchange.respondJson(200, ConfigResponse(mode.name))
        } catch (e: Exception) {
            exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
        }
        else -> exchange.respondJson(405, ApiError("GET or POST required"))
    }
}

/**
 * Clears cached data for a request, built exactly as [handleEnrich] builds one — `kind: "mbid"` is
 * rejected here because a bare MBID's request object is not reconstructable without the discovery
 * round-trip `handleEnrich` does; `forceRefresh` on that page's `/api/enrich` call covers it instead.
 */
private fun handleInvalidate(exchange: HttpExchange, engine: EnrichmentEngine) {
    if (exchange.requestMethod != "POST") {
        exchange.respondJson(405, ApiError("POST required"))
        return
    }
    try {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val request = try {
            json.decodeFromString<InvalidateRequest>(body)
        } catch (_: Exception) {
            exchange.respondJson(400, ApiError("malformed JSON body"))
            return
        }
        val kind = request.kind
        val name = request.name.trim()
        val artist = request.artist?.trim().orEmpty()
        val album = request.album?.trim()?.ifBlank { null }
        val mbid = request.mbid?.trim()?.ifBlank { null }

        val valid = kind in setOf("artist", "album", "track") &&
            name.isNotBlank() &&
            (kind == "artist" || artist.isNotBlank())
        if (!valid) {
            exchange.respondJson(
                400,
                ApiError("kind must be artist|album|track; name (and artist, for album/track) required"),
            )
            return
        }
        if (request.identifiers != null && kind != "track") {
            exchange.respondJson(400, ApiError("identifiers is only valid for kind=track"))
            return
        }
        // Threaded through so invalidation reaches the same identifier-bearing tuple the reload
        // that follows it will read (`docs/pitfalls.md` §5) — omitting this on a track request
        // would clear only the bare-name tuple and leave the identifier-bearing preview cached.
        val identifiers = try {
            request.identifiers?.let { validateTrackIdentifiers(it) }
        } catch (e: InvalidIdentifiers) {
            exchange.respondJson(400, ApiError(e.message ?: "invalid identifiers"))
            return
        }

        val enrichmentRequest = when (kind) {
            "artist" -> EnrichmentRequest.forArtist(name, mbid)
            "album" -> EnrichmentRequest.forAlbum(name, artist, mbid)
            else -> EnrichmentRequest.forTrack(name, artist, album, mbid = mbid, identifiers = identifiers)
        }
        runBlocking { engine.invalidate(enrichmentRequest, null) }
        exchange.respondJson(200, InvalidateResponse(true))
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

/**
 * The heading an MBID page falls back to when resolution named no entity: what the identifier
 * turned out to name, and enough of the identifier to recognise it by.
 *
 * A bare UUID as a page title reads as a name, which is the one thing it is not, so the type leads
 * and the id is trimmed to its first group. When resolution *did* name the entity, the canonical
 * title it read off that entity is the heading instead.
 */
private fun headerFor(entity: MusicBrainzEntityType, mbid: String): String =
    "${entity.name.lowercase().replaceFirstChar { it.uppercase() }} ${mbid.substringBefore('-')}"

/** [EnrichmentResult.Error] kinds a second attempt can plausibly fix — see [retryTransientFailures]. */
private val RETRYABLE_ERROR_KINDS = setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT)

/**
 * One bounded retry for this result's transient per-type failures.
 *
 * The engine deliberately never retries inside `enrich()` itself — `DefaultEnrichmentEngine`'s
 * `applyStaleCache` leaves the reasoning in a comment: "a stale entry that answers nothing is
 * worse than the Error it would replace: the Error at least tells the consumer to retry." A
 * library used from a one-shot CLI call and a long-running Android worker can't share one retry
 * budget, so the engine hands the decision to the caller. This is demo-web's: one immediate
 * retry, and only for [ErrorKind.NETWORK] and [ErrorKind.TIMEOUT] — the two kinds a blip on the
 * wire can plausibly fix. [ErrorKind.AUTH] and [ErrorKind.PARSE] would fail the same way again,
 * and [ErrorKind.UNKNOWN] also covers a broken `ResultMerger`/`CompositeSynthesizer` (core's
 * `StrategyGuard` default), which a retry can't fix either.
 *
 * Reuses this pass's resolved identity ([EnrichmentResults.identity]) so the retry doesn't
 * re-run identity resolution for a request that already resolved fine and only hit a transient
 * error on one provider type.
 */
private suspend fun EnrichmentResults.retryTransientFailures(
    engine: EnrichmentEngine,
    request: EnrichmentRequest,
): EnrichmentResults {
    val retryTypes = raw.filterValues { it is EnrichmentResult.Error && it.errorKind in RETRYABLE_ERROR_KINDS }.keys
    if (retryTypes.isEmpty()) return this
    val retryRequest = identity?.identifiers?.let(request::withIdentifiers) ?: request
    val retried = engine.enrich(retryRequest, retryTypes)
    return copy(raw = raw + retried.raw)
}

/**
 * Fetches [EnrichmentType.ARTIST_RADIO] for the track/album's artist — the narrowest engine call
 * that produces it, rather than a full [com.landofoz.musicmeta.artistProfile]. Radio is an
 * artist-only concept in the core type system, so track/album pages borrow the artist's. Any
 * failure or empty result just means no section — never surfaced to the caller, never blocking
 * the main profile fetch it runs alongside.
 */
private suspend fun fetchArtistRadioSection(engine: EnrichmentEngine, artist: String): Section? =
    try {
        val results = engine.enrich(EnrichmentRequest.forArtist(artist), setOf(EnrichmentType.ARTIST_RADIO))
        artistRadioSection(results.get<EnrichmentData.RadioPlaylist>(EnrichmentType.ARTIST_RADIO))
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive() // rethrows only if this job was cancelled
        null
    }

/**
 * Candidate lookup over `engine.search()`. Unlike [handleEnrich], an album or track query may
 * arrive without an artist — finding the artist is what a caller uses this for; a supplied one
 * only narrows the pool. `kind: "mbid"` is rejected: an identifier names its entity outright,
 * so there is nothing to search for.
 */
private fun handleSearch(exchange: HttpExchange, engine: EnrichmentEngine) {
    if (exchange.requestMethod != "GET") {
        exchange.respondJson(405, ApiError("GET required"))
        return
    }
    try {
        val params = parseQuery(exchange.requestURI.rawQuery)
        val kind = params["kind"]
        val query = params["q"]?.trim().orEmpty()
        val artist = params["artist"]?.trim().orEmpty()

        if (kind !in setOf("artist", "album", "track") || query.isBlank()) {
            exchange.respondJson(
                400,
                ApiError("kind must be artist|album|track; q required"),
            )
            return
        }

        val request = when (kind) {
            "artist" -> EnrichmentRequest.forArtist(query)
            "album" -> EnrichmentRequest.forAlbum(query, artist)
            else -> EnrichmentRequest.forTrack(query, artist)
        }

        // Eight rows is what the dropdown shows without scrolling.
        val candidates = runBlocking { engine.search(request, limit = 8) }
        exchange.respondJson(
            200,
            SearchResponse(
                candidates.map { candidate ->
                    CandidateHit(
                        title = candidate.title,
                        artist = candidate.artist,
                        year = candidate.year,
                        releaseType = candidate.releaseType,
                        score = candidate.score,
                        thumbnailUrl = candidate.thumbnailUrl,
                        disambiguation = candidate.disambiguation,
                        mbid = candidate.identifiers.musicBrainzId,
                    )
                },
            ),
        )
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

private fun handlePreview(exchange: HttpExchange, engine: EnrichmentEngine) {
    if (exchange.requestMethod != "GET") {
        exchange.respondJson(405, ApiError("GET required"))
        return
    }
    try {
        val params = parseQuery(exchange.requestURI.rawQuery)
        val title = params["title"]?.trim().orEmpty()
        val artist = params["artist"]?.trim().orEmpty()
        val album = params["album"]?.trim()?.ifBlank { null }
        if (title.isBlank() || artist.isBlank()) {
            exchange.respondJson(400, ApiError("title and artist are required"))
            return
        }
        val identifiers = try {
            decodeTrackIdentifiers(params["ids"])
        } catch (e: InvalidIdentifiers) {
            exchange.respondJson(400, ApiError(e.message ?: "invalid ids"))
            return
        }

        val preview = runBlocking {
            val request = if (identifiers != null) {
                TrackPreviewRequest(title, artist, album, identifiers)
            } else {
                TrackPreviewRequest(title, artist, album)
            }
            engine.resolveTrackPreviews(listOf(request)).first().preview
        }
        if (preview == null) {
            exchange.respondJson(404, ApiError("No preview available for \"$title\" by $artist"))
        } else {
            exchange.respondJson(200, PreviewResponse(preview.url, preview.durationMs, preview.source))
        }
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

/**
 * The providers this instance was built with, each joined to the terms snapshot `ProviderPolicies`
 * records under the same id — plus one row per [ProviderCatalog] entry `withDefaultProviders()`
 * never registered because its key was absent, so a keyless run still shows every provider it
 * *could* have. Reads state the engine already holds, so nothing here suspends.
 */
private fun handleProviders(exchange: HttpExchange, engine: EnrichmentEngine, apiKeys: ApiKeyConfig) {
    if (exchange.requestMethod != "GET") {
        exchange.respondJson(405, ApiError("GET required"))
        return
    }
    try {
        exchange.respondJson(200, ProvidersResponse(buildProviderRows(engine.getProviders(), apiKeys)))
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

/**
 * Merges live provider rows with [ProviderCatalog.entries]: a live [ProviderInfo] stays the
 * authority for anything registered, and a `Required` catalog entry absent from [live] whose own
 * selector reads null against [apiKeys] gets a catalog-only row (`keyStatus = "KEY_MISSING"`)
 * instead of silently vanishing from the table. Absence alone is not read as "key missing" — an
 * absent entry whose selector reads non-null (a future build that filters providers for some other
 * reason, or a registration failure) gets no row rather than a wrong claim about why it is gone. A
 * live `Optional` entry (ListenBrainz) is always present already; it gets `"TOKEN_MISSING"` when its
 * token selector reads null. A `None` entry absent from [live] is not synthesised — it isn't
 * key-gated, so its absence is a registration bug, not a key state this endpoint has an opinion on.
 *
 * The result is ordered by [ProviderCatalog.entries]'s own order, so the table reads the same
 * regardless of which ids happened to register; any live id the catalog doesn't know keeps its
 * original position relative to the other such ids, after every catalog id.
 */
internal fun buildProviderRows(live: List<ProviderInfo>, apiKeys: ApiKeyConfig): List<ProviderRow> {
    val catalogById = ProviderCatalog.entries.associateBy { it.id }
    val catalogOrder = ProviderCatalog.entries.withIndex().associate { (index, entry) -> entry.id to index }
    val liveIds = live.mapTo(mutableSetOf()) { it.id }

    val liveRows = live.map { info ->
        val requirement = catalogById[info.id]?.keyRequirement
        val keyStatus = (requirement as? KeyRequirement.Optional)
            ?.takeIf { it.key(apiKeys) == null }
            ?.let { "TOKEN_MISSING" }
        ProviderRow(
            id = info.id,
            displayName = info.displayName,
            available = info.isAvailable,
            requiresApiKey = info.requiresApiKey,
            capabilities = info.capabilities.map { it.type.name },
            policy = policyRow(info.id),
            keyStatus = keyStatus,
        )
    }

    val missingRows = ProviderCatalog.entries
        .filter { it.id !in liveIds }
        .mapNotNull { entry ->
            val requirement = entry.keyRequirement as? KeyRequirement.Required ?: return@mapNotNull null
            if (requirement.key(apiKeys) != null) return@mapNotNull null
            ProviderRow(
                id = entry.id,
                displayName = entry.displayName,
                available = false,
                requiresApiKey = true,
                policy = policyRow(entry.id),
                keyStatus = "KEY_MISSING",
            )
        }

    return (liveRows + missingRows).sortedWith(compareBy(nullsLast<Int>()) { catalogOrder[it.id] })
}

private fun policyRow(id: String): PolicyRow? = ProviderPolicies.all[id]?.let { policy ->
    PolicyRow(
        commercialUse = policy.commercialUse.name,
        dataLicence = policy.dataLicence,
        attribution = policy.attribution.name,
        attributionNotice = policy.attributionNotice,
    )
}

private fun parseQuery(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@mapNotNull null
        val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
        val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
        key to value
    }.toMap()
}

private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, body.size.toLong())
    responseBody.use { it.write(body) }
}

private inline fun <reified T> HttpExchange.respondJson(status: Int, body: T) {
    respond(status, "application/json; charset=utf-8", json.encodeToString(body).toByteArray(StandardCharsets.UTF_8))
}
