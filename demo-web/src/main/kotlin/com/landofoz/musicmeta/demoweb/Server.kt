package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.TrackPreviewRequest
import com.landofoz.musicmeta.albumProfile
import com.landofoz.musicmeta.artistProfile
import com.landofoz.musicmeta.resolveTrackPreviews
import com.landofoz.musicmeta.trackProfile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val json = Json { encodeDefaults = true }

/** Anchor class purely so [Class.getResourceAsStream] has a classloader to resolve against. */
private class ResourceAnchor

/**
 * Flips true once one real enrichment round-trip has completed — the JVM's JIT warmup plus each
 * provider's first-ever DNS resolution and TLS handshake, which is what pushes a genuinely cold
 * first request close to (or past) [com.landofoz.musicmeta.EnrichmentConfig.enrichTimeoutMs]. Every
 * request after that reuses warm connections and comfortably finishes inside the timeout.
 */
private val ready = AtomicBoolean(false)

@Serializable
private data class HealthResponse(val ready: Boolean)

fun startServer(engine: EnrichmentEngine, port: Int) {
    val indexHtml = ResourceAnchor::class.java.getResourceAsStream("/index.html")?.readBytes()
        ?: error("index.html missing from demo-web resources")
    val indexCss = ResourceAnchor::class.java.getResourceAsStream("/index.css")?.readBytes()
        ?: error("index.css missing from demo-web resources")
    val indexJs = ResourceAnchor::class.java.getResourceAsStream("/index.js")?.readBytes()
        ?: error("index.js missing from demo-web resources")

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/") { exchange ->
        when (exchange.requestURI.path) {
            "/" -> exchange.respond(200, "text/html; charset=utf-8", indexHtml)
            "/index.css" -> exchange.respond(200, "text/css; charset=utf-8", indexCss)
            "/index.js" -> exchange.respond(200, "text/javascript; charset=utf-8", indexJs)
            else -> exchange.respond(404, "text/plain", "not found".toByteArray())
        }
    }

    server.createContext("/api/enrich") { exchange -> handleEnrich(exchange, engine) }
    server.createContext("/api/preview") { exchange -> handlePreview(exchange, engine) }
    server.createContext("/api/health") { exchange -> exchange.respondJson(200, HealthResponse(ready.get())) }

    server.start()
    warmUp(engine)
}

/**
 * Fires a single throwaway enrichment off its own thread at startup, outside any request's timeout
 * budget, purely to pay the cold-start cost up front. Success or failure doesn't matter — only that
 * the round-trip happened — so any exception is swallowed.
 */
private fun warmUp(engine: EnrichmentEngine) {
    thread(name = "warmup", isDaemon = true) {
        try {
            runBlocking { engine.artistProfile("Radiohead") }
        } catch (_: Exception) {
            // best-effort — the point is paying the JIT/DNS/TLS cost, not the result
        } finally {
            ready.set(true)
        }
    }
}

private fun handleEnrich(exchange: HttpExchange, engine: EnrichmentEngine) {
    try {
        val params = parseQuery(exchange.requestURI.rawQuery)
        val kind = params["kind"]
        val name = params["name"]?.trim().orEmpty()
        val artist = params["artist"]?.trim().orEmpty()
        val album = params["album"]?.trim()?.ifBlank { null }

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

        val started = System.currentTimeMillis()
        val response = runBlocking {
            when (kind) {
                "artist" -> {
                    val profile = engine.artistProfile(name)
                    val retried = profile.results.retryTransientFailures(engine, EnrichmentRequest.forArtist(name))
                    profile.copy(results = retried).toDemoResponse(System.currentTimeMillis() - started)
                }
                "album" ->
                    coroutineScope {
                        val profileDeferred = async { engine.albumProfile(name, artist) }
                        val radioDeferred = async { fetchArtistRadioSection(engine, artist) }
                        val profile = profileDeferred.await()
                        val retried = profile.results.retryTransientFailures(
                            engine,
                            EnrichmentRequest.forAlbum(name, artist),
                        )
                        val radio = radioDeferred.await()
                        profile.copy(results = retried).toDemoResponse(System.currentTimeMillis() - started, radio)
                    }
                else ->
                    coroutineScope {
                        val profileDeferred = async { engine.trackProfile(name, artist, album) }
                        val radioDeferred = async { fetchArtistRadioSection(engine, artist) }
                        val profile = profileDeferred.await()
                        val retried = profile.results.retryTransientFailures(
                            engine,
                            EnrichmentRequest.forTrack(name, artist, album),
                        )
                        val radio = radioDeferred.await()
                        profile.copy(results = retried)
                            .toDemoResponse(System.currentTimeMillis() - started, radio, album)
                    }
            }
        }
        exchange.respondJson(200, response)
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
}

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

private fun handlePreview(exchange: HttpExchange, engine: EnrichmentEngine) {
    try {
        val params = parseQuery(exchange.requestURI.rawQuery)
        val title = params["title"]?.trim().orEmpty()
        val artist = params["artist"]?.trim().orEmpty()
        val album = params["album"]?.trim()?.ifBlank { null }
        if (title.isBlank() || artist.isBlank()) {
            exchange.respondJson(400, ApiError("title and artist are required"))
            return
        }

        val preview = runBlocking {
            engine.resolveTrackPreviews(listOf(TrackPreviewRequest(title, artist, album))).first().preview
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
