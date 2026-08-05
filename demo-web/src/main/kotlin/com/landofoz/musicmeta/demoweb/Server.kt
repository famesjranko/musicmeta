package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.TrackPreviewRequest
import com.landofoz.musicmeta.albumProfile
import com.landofoz.musicmeta.artistProfile
import com.landofoz.musicmeta.resolveTrackPreviews
import com.landofoz.musicmeta.trackProfile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

private val json = Json { encodeDefaults = true }

/** Anchor class purely so [Class.getResourceAsStream] has a classloader to resolve against. */
private class ResourceAnchor

fun startServer(engine: EnrichmentEngine, port: Int) {
    val indexHtml = ResourceAnchor::class.java.getResourceAsStream("/index.html")?.readBytes()
        ?: error("index.html missing from demo-web resources")

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/") { exchange ->
        if (exchange.requestURI.path == "/") {
            exchange.respond(200, "text/html; charset=utf-8", indexHtml)
        } else {
            exchange.respond(404, "text/plain", "not found".toByteArray())
        }
    }

    server.createContext("/api/enrich") { exchange -> handleEnrich(exchange, engine) }
    server.createContext("/api/preview") { exchange -> handlePreview(exchange, engine) }

    server.start()
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
                "artist" -> engine.artistProfile(name).toDemoResponse(System.currentTimeMillis() - started)
                "album" -> engine.albumProfile(name, artist).toDemoResponse(System.currentTimeMillis() - started)
                else ->
                    engine.trackProfile(name, artist, album)
                        .toDemoResponse(System.currentTimeMillis() - started)
            }
        }
        exchange.respondJson(200, response)
    } catch (e: Exception) {
        exchange.respondJson(500, ApiError(e.message ?: e.javaClass.simpleName))
    }
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
