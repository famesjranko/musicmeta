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
import kotlinx.coroutines.delay
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
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

private val json = Json { encodeDefaults = true }

/** Anchor class purely so [Class.getResourceAsStream] has a classloader to resolve against. */
private class ResourceAnchor

/**
 * Every file the page fetches by URL. `/` serves `/index.html`; the rest are fetched as they are
 * named, including each module `index.js` imports — the page is a module graph, not one script, so
 * a module missing from this list is a module the browser 404s on and a page that does not run.
 * `/robots.txt` rides here too: not a page module, but a crawler-facing file the server must serve.
 */
internal val STATIC_PATHS = listOf(
    "/index.html",
    "/index.css",
    "/index.js",
    "/stream-protocol.js",
    "/attribution.js",
    "/robots.txt",
)

internal fun staticContentTypeOf(path: String): String = when {
    path.endsWith(".html") -> "text/html; charset=utf-8"
    path.endsWith(".css") -> "text/css; charset=utf-8"
    path.endsWith(".txt") -> "text/plain; charset=utf-8"
    else -> "text/javascript; charset=utf-8"
}

private const val MAX_IDS_PARAM_LENGTH = 512
private const val MAX_IDENTIFIER_VALUE_LENGTH = 100

/**
 * Free-text query params (`name`, `artist`, `album`, `q`, `title`) that ride into a provider
 * search rather than address one identifier — mirroring [MAX_IDS_PARAM_LENGTH]. Always-on, not
 * posture-gated: 256 is longer than any real track/artist/album title and far below the junk an
 * abusive request could otherwise carry into a provider fan-out, so no legitimate local request is
 * affected — same reasoning as [MAX_IDS_PARAM_LENGTH]'s existing unconditional cap.
 */
private const val MAX_QUERY_PARAM_LENGTH = 256

/**
 * Ceiling on a `POST` body this server will buffer, for `/api/config` and `/api/invalidate` alike.
 * Both bodies are small fixed-shape JSON objects; 64 KiB is generous headroom over either, so no
 * legitimate request is affected. Always-on for the same reason [MAX_QUERY_PARAM_LENGTH] is: it
 * sits far above any real body, so gating it on posture would buy nothing.
 */
private const val MAX_POST_BODY_BYTES = 64 * 1024

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

@Serializable
internal data class HealthResponse(val ready: Boolean, val status: String)

/**
 * The one answer `/api/health` gives — a **liveness** probe, not a readiness one. The engine is
 * built before [startServer] is called and the listening socket is bound only after every context
 * is registered, so a request that reaches this endpoint has reached a server able to serve every
 * other one: there is no state left to report readiness over.
 *
 * It says nothing about the providers. A provider's own misconfiguration is the providers panel's
 * job (see the key-missing rows [buildProviderRows] adds), and an uptime probe wired to this
 * endpoint should not flap on an upstream outage the process itself survives.
 */
private val HEALTH_READY = HealthResponse(ready = true, status = "READY")

/**
 * [engineRef] is read fresh per request rather than captured once, so a cache-mode swap takes
 * effect on the very next call. [rebuildEngine] must build every engine over the same shared
 * cache instance — that's what lets STALE_IF_ERROR serve entries a NETWORK_FIRST run warmed
 * before the swap; see [handleConfig].
 *
 * [unregisteredProviderIds] names providers this instance declines to register whatever
 * [apiKeys] holds; `/api/providers` leaves them out entirely rather than reporting a key state
 * for a provider whose absence is a policy, not a configuration.
 *
 * [requireMaintainerSecret] and [maintainerSecret] gate the mutating half of `/api/config` — see
 * [handleConfig]. [securityHeaders] adds the static response-header set every context here sends
 * before dispatch, covering the SSE path (which sets its own headers directly) the same as every
 * other. Both default off, which is what keeps a `DEMO_PUBLIC`-unset process's answers unchanged.
 */
fun startServer(
    engineRef: AtomicReference<EnrichmentEngine>,
    cacheModeRef: AtomicReference<CacheMode>,
    rebuildEngine: (CacheMode) -> EnrichmentEngine,
    apiKeys: ApiKeyConfig,
    port: Int,
    unregisteredProviderIds: Set<String> = emptySet(),
    requireMaintainerSecret: Boolean = false,
    maintainerSecret: String? = null,
    securityHeaders: Boolean = false,
) {
    val staticFiles = STATIC_PATHS.associateWith { path ->
        ResourceAnchor::class.java.getResourceAsStream(path)?.readBytes()
            ?: error("$path missing from demo-web resources")
    }

    val server = HttpServer.create(InetSocketAddress(port), 0)
    // A streaming request holds its thread for the whole enrichment, not for one round trip, so the
    // pool has to cover concurrent readers *and* leave room for the health probe and the provider
    // panel. 16 is headroom for a demo, not a measured capacity.
    server.executor = Executors.newFixedThreadPool(16)

    // One place every context routes through before its own handler runs, so a header every
    // response should carry needs adding once rather than at each handler — including the SSE
    // path, which writes its own headers block but shares this same dispatch.
    fun registerContext(path: String, handle: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            exchange.addSecurityHeaders(securityHeaders)
            handle(exchange)
        }
    }

    registerContext("/") { exchange ->
        val path = exchange.requestURI.path.let { if (it == "/") "/index.html" else it }
        val body = staticFiles[path]
        if (body == null) {
            exchange.respond(404, "text/plain", "not found".toByteArray())
        } else {
            exchange.respond(200, staticContentTypeOf(path), body)
        }
    }

    // Every endpoint that can reach a provider goes through one client's budget first. The three
    // that cannot — health, providers, config — are registered plainly below: charging for them
    // would buy no upstream any protection and would refuse the page's own health poll.
    val budget = ClientBudget()
    fun upstreamContext(path: String, handle: (HttpExchange) -> Unit) {
        registerContext(path) { exchange ->
            if (budget.admit(exchange.clientKey())) {
                handle(exchange)
            } else {
                exchange.refuseAsBusy("This client has asked for a lot at once. Try again shortly.")
            }
        }
    }

    upstreamContext("/api/enrich") { exchange -> handleEnrich(exchange, engineRef.get()) }
    // Longest matching prefix wins in this server, so this context takes the path despite
    // `/api/enrich` also matching it.
    upstreamContext("/api/enrich-stream") { exchange -> handleEnrichStream(exchange, engineRef.get()) }
    upstreamContext("/api/invalidate") { exchange -> handleInvalidate(exchange, engineRef.get()) }
    upstreamContext("/api/search") { exchange -> handleSearch(exchange, engineRef.get()) }
    upstreamContext("/api/preview") { exchange -> handlePreview(exchange, engineRef.get()) }
    registerContext("/api/providers") { exchange ->
        handleProviders(exchange, engineRef.get(), apiKeys, unregisteredProviderIds)
    }
    registerContext("/api/config") { exchange ->
        handleConfig(exchange, engineRef, cacheModeRef, rebuildEngine, requireMaintainerSecret, maintainerSecret)
    }
    registerContext("/api/health") { exchange -> exchange.respondJson(200, HEALTH_READY) }

    server.start()
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

/**
 * Throws [InvalidEnrichQuery] when [value] exceeds [MAX_QUERY_PARAM_LENGTH] — the shared check
 * behind every free-text query param this server accepts (`parseEnrichQuery`'s `name`/`artist`/
 * `album`, `handleSearch`'s `q`/`artist`, `handlePreview`'s `title`/`artist`/`album`), so the three
 * sites can't drift on the limit or the message.
 */
private fun capQueryParam(label: String, value: String) {
    if (value.length > MAX_QUERY_PARAM_LENGTH) {
        throw InvalidEnrichQuery("$label exceeds the size limit ($MAX_QUERY_PARAM_LENGTH)")
    }
}

private fun parseEnrichQuery(rawQuery: String?): EnrichQuery {
    val params = parseQuery(rawQuery)
    val kind = params["kind"]
    val name = params["name"]?.trim().orEmpty()
    val artist = params["artist"]?.trim().orEmpty()
    val album = params["album"]?.trim()?.ifBlank { null }
    capQueryParam("name", name)
    capQueryParam("artist", artist)
    album?.let { capQueryParam("album", it) }
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
        album = album,
        mbid = params["mbid"]?.trim()?.ifBlank { null },
        identifiers = identifiers,
        forceRefresh = params["refresh"] == "true",
    )
}

/**
 * How many enrichments this instance runs at once.
 *
 * A bound, deliberately not a serialiser. `ProgressiveRunRegistry` collapses concurrent lookups of
 * one entity into a single fan-out, and it can only do that while they overlap in time — admitting
 * one at a time would guarantee they never do, turning the cheapest case, a crowd on one album,
 * into the slowest. Five is the figure the measurement used and was never swept.
 *
 * Only correct while the service runs at one instance: the bound below is per-process, so a second
 * instance is a second gate and this is the cap per instance rather than the cap. Nothing in this
 * process can read that deployment setting, so nothing here can check it.
 */
internal const val MAX_IN_GATE = 5

/** What a refused caller is told to wait, in seconds. The page reads it to say how long. */
private const val RETRY_AFTER_SECONDS = "15"

/**
 * Turns a caller away in the one shape the page renders as its busy state.
 *
 * Two things refuse for two reasons — a full gate and a spent client budget — and a visitor has the
 * same thing to do about either, so they answer identically rather than each inventing a status and
 * a body. [message] is the operator's account of which it was.
 */
private fun HttpExchange.refuseAsBusy(message: String) {
    responseHeaders.add("Retry-After", RETRY_AFTER_SECONDS)
    respondJson(429, ApiError(message))
}

/**
 * This request's client, as [clientKeyFrom] reads it off the exchange. All header lines are
 * joined before the parse: the platform appends its entry to the header as a whole, so reading
 * only the first line would hand the charge to a line the caller wrote for itself.
 */
private fun HttpExchange.clientKey(): String =
    clientKeyFrom(requestHeaders["X-Forwarded-For"]?.joinToString(","), remoteAddress.address.hostAddress)

private val enrichmentGate = Semaphore(MAX_IN_GATE)

/**
 * Whether the gate has nothing left to hand out — the instance's own definition of being under
 * pressure, read from the one piece of state that already knows.
 *
 * Asked by a request that is itself holding a permit, so "none free" means every other permit is
 * spent too and the next arrival is refused. That is the point at which a `NETWORK` or `TIMEOUT`
 * error stops being good evidence of a blip on the wire: this instance is contended, and the work
 * that would recover one type is the work that is starving the rest.
 */
private fun enrichmentGateIsFull(): Boolean = enrichmentGate.availablePermits() == 0

/**
 * How often a stream writes a comment purely to learn whether the reader is still there. The bound
 * on how long an abandoned stream can keep its admission permit, so it stays well under
 * [RETRY_AFTER_SECONDS] — a refusal caused by permits the leavers still hold would outlive its own
 * advice.
 */
private const val HEARTBEAT_MS = 1_000L

/**
 * Runs [enriching] holding one of [MAX_IN_GATE] permits, or refuses outright without running it.
 *
 * Nothing queues. A caller that cannot get in is told so at once, in a body the page can render —
 * which is the point of refusing here rather than leaving the load for the platform to shed.
 *
 * Call this after the cheap refusals and before any status is committed. A request that could never
 * have enriched anything must not spend a permit to find that out; and [handleEnrichStream] sends
 * its 200 before it enriches, after which the only way left to say "busy" is an event inside a
 * successful response, where no status code and no proxy can see it.
 */
private inline fun HttpExchange.withEnrichmentPermit(enriching: () -> Unit) {
    if (!enrichmentGate.tryAcquire()) {
        refuseAsBusy("The demo runs $MAX_IN_GATE lookups at a time and is at capacity. Try again shortly.")
        return
    }
    try {
        enriching()
    } finally {
        enrichmentGate.release()
    }
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
    exchange.withEnrichmentPermit {
        try {
            val kind = query.kind
            val name = query.name
            val artist = query.artist
            val album = query.album
            val mbid = query.mbid
            val identifiers = query.identifiers
            val forceRefresh = query.forceRefresh

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
}

/**
 * The identity status a mid-stream snapshot cannot yet read as a verdict. [CanonicalStatus.RESOLVING]
 * is the only status resolution can still revise before the terminal snapshot; the `NOT_ATTEMPTED_*`
 * family means resolution was never going to run, which is already the final word, not a
 * placeholder — so a snapshot carrying `RESOLVING` presents its header as provisional and one
 * carrying `NOT_ATTEMPTED_*` treats it as an answer, same as the terminal snapshot does.
 */
private val UNSETTLED_IDENTITY_STATUSES = setOf(CanonicalStatus.RESOLVING)

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

    exchange.withEnrichmentPermit {
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
            runBlocking {
                // The next snapshot can be tens of seconds away behind provider rate limiters, and a
                // reader who left is only ever noticed by a failed write — so without one, this
                // handler holds its admission permit for the whole abandoned run, and a single
                // visitor clicking through lookups can refuse themselves. The heartbeat is that
                // write: its failure cancels this scope, collection and all.
                val heartbeat = launch {
                    while (true) {
                        delay(HEARTBEAT_MS)
                        writer.comment()
                    }
                }
                try {
                    streamEnrichment(engine, query, started, writer)
                } finally {
                    heartbeat.cancel()
                }
            }
        } catch (e: Exception) {
            // `runBlocking` surfaces a cancellation from inside it as a plain throw on this thread, so
            // there is no ambient job here for `ensureActive()` to consult.
            reportStreamFailure(writer, e)
        } finally {
            writer.close()
        }
    }
}

/**
 * Reports a failed run as the stream's terminal `error` event.
 *
 * A status code is not available - the status line went out with the headers - so the event is the
 * only way left to say anything. But it is only worth saying to a client that is still there, and
 * the commonest way a stream ends is the reader leaving: then the failure being reported *is* the
 * transport, and writing the report onto the socket that just refused a write throws a second time.
 * A dropped client is recorded once and dropped. The remaining attempt is guarded too, because a
 * client can leave between the last successful write and this one.
 */
internal fun reportStreamFailure(writer: SseWriter, cause: Exception) {
    if (writer.isBroken) {
        println("enrich stream: client left mid-stream (${cause.javaClass.simpleName}: ${cause.message})")
        return
    }
    try {
        writer.write("error", json.encodeToString(ApiError(cause.message ?: cause.javaClass.simpleName)))
    } catch (e: IOException) {
        println("enrich stream: client left before the failure could be reported (${e.message})")
    }
}

/**
 * Frames server-sent events onto one response body, flushing each so nothing waits on a buffer
 * filling. Every write happens on the request thread — the collector below runs on `runBlocking`'s
 * own event loop — so this holds no lock.
 */
internal class SseWriter(private val out: OutputStream) {

    /**
     * True once a write has been refused. The reader is gone, so nothing written after this can
     * reach anyone — which is what stops a failure report being aimed at a dead socket.
     */
    var isBroken: Boolean = false
        private set

    /**
     * Writes a comment line no client renders; its only observable effect is throwing [IOException]
     * when the reader has left. SSE defines the `:` line as ignorable, and the page's parser skips it.
     */
    fun comment() {
        try {
            out.write(": hb\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (e: IOException) {
            isBroken = true
            throw e
        }
    }

    /** Throws [IOException] when the client has left; the caller treats that as the end of the stream. */
    fun write(event: String, data: String) {
        // A newline inside the payload would otherwise be read as a second data line, or worse as
        // the blank line that ends the event. JSON emits none, but framing must not rest on that.
        val body = data.lineSequence().joinToString("\n") { "data: $it" }
        try {
            out.write("event: $event\n$body\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (e: IOException) {
            isBroken = true
            throw e
        }
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
 * Constant-time check of the `X-Maintainer-Secret` header against [expected]. False whenever
 * [expected] is null/blank — a public instance with no secret configured has nothing to compare
 * against and must fail closed, never treat "nothing to check" as "check passed".
 * [MessageDigest.isEqual] rather than [String.equals]: a short-circuiting compare leaks the
 * secret's prefix length through response timing to anyone who can send repeated guesses.
 */
private fun HttpExchange.hasValidSecret(expected: String?): Boolean {
    if (expected.isNullOrBlank()) return false
    val provided = requestHeaders.getFirst("X-Maintainer-Secret") ?: return false
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        provided.toByteArray(StandardCharsets.UTF_8),
    )
}

/**
 * Reads at most [max] bytes of this exchange's request body, refusing before buffering when a
 * declared `Content-Length` already exceeds [max], and refusing after reading at most `max + 1`
 * bytes when it does not — a chunked request carries no `Content-Length` at all, so the declared
 * check alone would let an unbounded chunked body reach [InputStream.readBytes] unguarded. Returns
 * null for either refusal; the caller answers 413 before attempting to decode anything.
 */
private fun HttpExchange.readBodyCapped(max: Int): ByteArray? {
    val declared = requestHeaders.getFirst("Content-Length")?.toLongOrNull()
    if (declared != null && declared > max) return null
    val body = requestBody.readNBytes(max + 1)
    if (body.size > max) return null
    return body
}

/**
 * `GET` reports the running [CacheMode]; `POST` rebuilds the engine over it. The rebuilt engine is
 * handed the same shared cache every other build used — see [startServer] — so cached entries
 * survive the swap and a STALE_IF_ERROR toggle has something to serve as a fallback.
 *
 * `POST` is gated behind [hasValidSecret] whenever [requireMaintainerSecret] is set — a public
 * instance's own control, not a visitor's — checked before any body read so a rejected caller
 * never spends the body cap either. `GET` stays open under every posture: it is read-only and the
 * page polls it on load.
 */
private fun handleConfig(
    exchange: HttpExchange,
    engineRef: AtomicReference<EnrichmentEngine>,
    cacheModeRef: AtomicReference<CacheMode>,
    rebuildEngine: (CacheMode) -> EnrichmentEngine,
    requireMaintainerSecret: Boolean,
    maintainerSecret: String?,
) {
    when (exchange.requestMethod) {
        "GET" -> exchange.respondJson(200, ConfigResponse(cacheModeRef.get().name, requireMaintainerSecret))
        "POST" -> try {
            if (requireMaintainerSecret && !exchange.hasValidSecret(maintainerSecret)) {
                exchange.respondJson(403, ApiError("maintainer secret required"))
                return
            }
            val bytes = exchange.readBodyCapped(MAX_POST_BODY_BYTES) ?: run {
                exchange.respondJson(413, ApiError("request body too large"))
                return
            }
            val body = bytes.toString(StandardCharsets.UTF_8)
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
                exchange.respondJson(200, ConfigResponse(mode.name, requireMaintainerSecret))
                return
            }
            // cacheModeRef first: the window this leaves is a GET briefly reporting the new mode
            // while engineRef still serves the old one — never the reverse, where a GET would report
            // a mode the running engine has already left behind.
            cacheModeRef.set(mode)
            engineRef.set(rebuildEngine(mode))
            exchange.respondJson(200, ConfigResponse(mode.name, requireMaintainerSecret))
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
        val bytes = exchange.readBodyCapped(MAX_POST_BODY_BYTES) ?: run {
            exchange.respondJson(413, ApiError("request body too large"))
            return
        }
        val body = bytes.toString(StandardCharsets.UTF_8)
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
 *
 * Skipped outright while the gate is full ([enrichmentGateIsFull]): the caller keeps the `Error`,
 * which already tells a consumer to retry, and does not spend a second fan-out to learn it again.
 */
private suspend fun EnrichmentResults.retryTransientFailures(
    engine: EnrichmentEngine,
    request: EnrichmentRequest,
): EnrichmentResults {
    val retryTypes = raw.filterValues { it is EnrichmentResult.Error && it.errorKind in RETRYABLE_ERROR_KINDS }.keys
    if (retryTypes.isEmpty()) return this
    if (enrichmentGateIsFull()) return this
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
        artistRadioSection(artist, results)
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
        capQueryParam("q", query)
        capQueryParam("artist", artist)

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
    } catch (e: InvalidEnrichQuery) {
        exchange.respondJson(400, ApiError(e.message ?: "invalid request"))
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
        capQueryParam("title", title)
        capQueryParam("artist", artist)
        album?.let { capQueryParam("album", it) }
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
    } catch (e: InvalidEnrichQuery) {
        exchange.respondJson(400, ApiError(e.message ?: "invalid request"))
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
private fun handleProviders(
    exchange: HttpExchange,
    engine: EnrichmentEngine,
    apiKeys: ApiKeyConfig,
    unregisteredProviderIds: Set<String>,
) {
    if (exchange.requestMethod != "GET") {
        exchange.respondJson(405, ApiError("GET required"))
        return
    }
    try {
        val rows = buildProviderRows(engine.getProviders(), apiKeys, unregisteredProviderIds)
        exchange.respondJson(200, ProvidersResponse(rows))
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
 * An id in [unregisteredProviderIds] gets no row at all, live or synthesised: this instance
 * declines to register it whatever [apiKeys] holds, so any key state shown against it would be
 * an answer to a question the table isn't asking.
 *
 * The result is ordered by [ProviderCatalog.entries]'s own order, so the table reads the same
 * regardless of which ids happened to register; any live id the catalog doesn't know keeps its
 * original position relative to the other such ids, after every catalog id.
 */
internal fun buildProviderRows(
    live: List<ProviderInfo>,
    apiKeys: ApiKeyConfig,
    unregisteredProviderIds: Set<String> = emptySet(),
): List<ProviderRow> {
    val catalogById = ProviderCatalog.entries.associateBy { it.id }
    val catalogOrder = ProviderCatalog.entries.withIndex().associate { (index, entry) -> entry.id to index }
    val liveIds = live.mapTo(mutableSetOf()) { it.id }

    val liveRows = live.filterNot { it.id in unregisteredProviderIds }.map { info ->
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
        .filter { it.id !in liveIds && it.id !in unregisteredProviderIds }
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

/**
 * The minimal static header set a public instance adds to every response, including the SSE path
 * — which sets its own headers directly and does not go through [respond]. A no-op when [enabled]
 * is false, which is what keeps a `DEMO_PUBLIC`-unset process's response bytes unchanged: these
 * headers are observable on every response, unlike the always-on size caps above, so gating them
 * on posture is what the forbidden-state rule needs here.
 *
 * `default-src 'self'` covers this page's script loading (`index.js` and its module graph are all
 * same-origin). `img-src` widens to any origin because every provider's own image URL is embedded
 * as-is — the whole point of the demo — never proxied same-origin. `style-src` allows
 * `'unsafe-inline'`: `index.html` carries two `style="display:none"` attributes, and index.js's
 * `element.style.<property> = …` assignments (not `cssText`, which CSP does gate) render fine
 * either way, so the widening is for the static attributes, not the script. `media-src` widens for
 * the same reason `img-src` does: a 30s preview is streamed from the provider's own CDN, whose
 * hostnames the provider rotates, so pinning them would break playback silently.
 */
private fun HttpExchange.addSecurityHeaders(enabled: Boolean) {
    if (!enabled) return
    responseHeaders.add("X-Content-Type-Options", "nosniff")
    responseHeaders.add("X-Frame-Options", "DENY")
    responseHeaders.add("Referrer-Policy", "no-referrer")
    responseHeaders.add(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' https: data:; media-src https:; " +
            "style-src 'self' 'unsafe-inline'",
    )
    responseHeaders.add("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
}

private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, body.size.toLong())
    responseBody.use { it.write(body) }
}

private inline fun <reified T> HttpExchange.respondJson(status: Int, body: T) {
    respond(status, "application/json; charset=utf-8", json.encodeToString(body).toByteArray(StandardCharsets.UTF_8))
}
