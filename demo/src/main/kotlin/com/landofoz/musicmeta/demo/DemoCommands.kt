package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.albumProfile
import com.landofoz.musicmeta.artistProfile
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.trackProfile
import com.landofoz.musicmeta.demo.ui.Spinner
import com.landofoz.musicmeta.demo.ui.Terminal
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun handleConfig(args: String, state: DemoState, term: Terminal) {
    val parts = args.split(" ", limit = 2)
    val key = parts[0].lowercase()
    val value = parts.getOrNull(1)?.trim()

    if (value == null) {
        term.info("Usage: config <key> <value>")
        term.info("Keys: timeout <ms>, confidence <0.0-1.0>, identity on|off, http default|okhttp, stale on|off")
        return
    }

    when (key) {
        "timeout" -> {
            val ms = value.toLongOrNull()
            if (ms == null || ms < 0) { term.info("Invalid timeout: $value"); return }
            state.config = state.config.copy(enrichTimeoutMs = ms)
            state.rebuild()
            term.info("Timeout set to ${ms}ms")
        }
        "confidence" -> {
            val c = value.toFloatOrNull()
            if (c == null || c !in 0f..1f) { term.info("Invalid confidence: $value (use 0.0-1.0)"); return }
            state.config = state.config.copy(minConfidence = c)
            state.rebuild()
            term.info("Min confidence set to ${"%.2f".format(c)}")
        }
        "identity" -> {
            val on = value.equals("on", ignoreCase = true) || value == "true"
            val off = value.equals("off", ignoreCase = true) || value == "false"
            if (!on && !off) { term.info("Usage: config identity on|off"); return }
            state.config = state.config.copy(enableIdentityResolution = on)
            state.rebuild()
            term.info("Identity resolution: ${if (on) "on" else "off"}")
        }
        "http" -> {
            val backend = when (value.lowercase()) {
                "default" -> HttpBackend.DEFAULT
                "okhttp" -> HttpBackend.OKHTTP
                else -> { term.info("Usage: config http default|okhttp"); return }
            }
            state.httpBackend = backend
            state.rebuild()
            term.info("HTTP backend: ${backend.name.lowercase()}")
        }
        "stale" -> {
            val on = value.equals("on", ignoreCase = true) || value == "true"
            val off = value.equals("off", ignoreCase = true) || value == "false"
            if (!on && !off) { term.info("Usage: config stale on|off"); return }
            val mode = if (on) CacheMode.STALE_IF_ERROR else CacheMode.NETWORK_FIRST
            state.config = state.config.copy(cacheMode = mode)
            state.rebuild()
            term.info("Cache mode: ${mode.name.lowercase().replace("_", " ")}")
        }
        else -> term.info("Unknown config key: $key. Try: timeout, confidence, identity, http, stale")
    }
}

fun handleCache(args: String, state: DemoState, term: Terminal) {
    when (args.lowercase()) {
        "clear" -> {
            runBlocking { state.cache.clear() }
            term.info("Cache cleared.")
        }
        else -> {
            term.info("Usage: cache [clear]")
            term.info("Related: refresh <entity>, invalidate <entity>")
        }
    }
}

fun executeRefresh(input: String, state: DemoState, term: Terminal, spinner: Spinner) {
    val (cleanInput, customTypes) = extractTypes(input)
    val request = parseEntityRequest(cleanInput)
    if (request == null) {
        term.info("Usage: refresh artist|album|track <name> [by <artist>]")
        return
    }
    val types = customTypes ?: defaultTypesFor(request)
    val label = "Refreshing ${entityKind(request)}..."
    runBlocking {
        val profile = if (state.logger.enabled) {
            term.info(label)
            enrichProfile(state, request, types, forceRefresh = true)
        } else {
            spinner.spin(label) { enrichProfile(state, request, types, forceRefresh = true) }
        }
        printEnrichedProfile(profile, request, term, cacheHits = 0)
    }
}

fun handleInvalidate(input: String, state: DemoState, term: Terminal) {
    val request = parseEntityRequest(input)
    if (request == null) {
        term.info("Usage: invalidate artist|album|track <name> [by <artist>]")
        return
    }
    runBlocking { state.engine.invalidate(request) }
    term.info("Cache invalidated for ${entityKind(request)}.")
}

/** Calls the appropriate profile extension with forceRefresh support. Returns (results, suggestions). */
internal suspend fun enrichProfile(
    state: DemoState,
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    forceRefresh: Boolean = false,
): EnrichedProfile = when (request) {
    is EnrichmentRequest.ForArtist -> {
        val p = state.engine.artistProfile(request.name, request.identifiers.musicBrainzId, types, forceRefresh)
        EnrichedProfile.Artist(p)
    }
    is EnrichmentRequest.ForAlbum -> {
        val p = state.engine.albumProfile(request.title, request.artist, request.identifiers.musicBrainzId, types, forceRefresh)
        EnrichedProfile.Album(p)
    }
    is EnrichmentRequest.ForTrack -> {
        val p = state.engine.trackProfile(request.title, request.artist, mbid = request.identifiers.musicBrainzId, types = types, forceRefresh = forceRefresh)
        EnrichedProfile.Track(p)
    }
}

internal fun printEnrichedProfile(profile: EnrichedProfile, request: EnrichmentRequest, term: Terminal, cacheHits: Int) {
    when (profile) {
        is EnrichedProfile.Artist -> Formatter.printProfile(profile.value, term, cacheHits)
        is EnrichedProfile.Album -> Formatter.printProfile(profile.value, term, cacheHits)
        is EnrichedProfile.Track -> Formatter.printProfile(profile.value, term, cacheHits)
    }
}

internal fun defaultTypesFor(request: EnrichmentRequest): Set<EnrichmentType> = when (request) {
    is EnrichmentRequest.ForArtist -> ARTIST_TYPES
    is EnrichmentRequest.ForAlbum -> ALBUM_TYPES
    is EnrichmentRequest.ForTrack -> TRACK_TYPES
}

/** Wrapper so the caller can handle all three profile types uniformly. */
internal sealed class EnrichedProfile {
    abstract val suggestions: List<com.landofoz.musicmeta.SearchCandidate>
    data class Artist(val value: com.landofoz.musicmeta.ArtistProfile) : EnrichedProfile() {
        override val suggestions get() = value.suggestions
    }
    data class Album(val value: com.landofoz.musicmeta.AlbumProfile) : EnrichedProfile() {
        override val suggestions get() = value.suggestions
    }
    data class Track(val value: com.landofoz.musicmeta.TrackProfile) : EnrichedProfile() {
        override val suggestions get() = value.suggestions
    }
}

fun toggleVerbose(state: DemoState, term: Terminal) {
    state.logger.enabled = !state.logger.enabled
    term.info("Verbose logging: ${if (state.logger.enabled) "on" else "off"}")
}

fun handleCatalog(args: String, state: DemoState, term: Terminal) {
    val parts = args.split(" ", limit = 2)
    val cmd = parts[0].lowercase()
    val value = parts.getOrNull(1)?.trim()

    when (cmd) {
        "add" -> {
            if (value.isNullOrBlank()) { term.info("Usage: catalog add <artist>"); return }
            state.catalog.artists.add(value)
            if (state.catalogMode == CatalogFilterMode.UNFILTERED) {
                state.catalogMode = CatalogFilterMode.AVAILABLE_FIRST
                state.rebuild()
                term.info("Added \"$value\". Catalog mode auto-set to available-first.")
            } else {
                term.info("Added \"$value\" to demo catalog.")
            }
        }
        "remove" -> {
            if (value.isNullOrBlank()) { term.info("Usage: catalog remove <artist>"); return }
            if (state.catalog.artists.removeIf { it.equals(value, ignoreCase = true) }) {
                term.info("Removed \"$value\".")
            } else {
                term.info("\"$value\" not in catalog.")
            }
        }
        "mode" -> {
            if (value == null) { term.info("Usage: catalog mode off|only|first"); return }
            val mode = when (value.lowercase()) {
                "off", "unfiltered" -> CatalogFilterMode.UNFILTERED
                "only", "available_only", "available-only" -> CatalogFilterMode.AVAILABLE_ONLY
                "first", "available_first", "available-first" -> CatalogFilterMode.AVAILABLE_FIRST
                else -> { term.info("Unknown mode: $value. Try: off, only, first"); return }
            }
            state.catalogMode = mode
            state.rebuild()
            term.info("Catalog filter mode: ${mode.name.lowercase().replace("_", " ")}")
        }
        else -> term.info("Usage: catalog add|remove <artist> | catalog mode off|only|first")
    }
}

/** Extracts --types flag from input, returns (clean command, custom types or null). */
fun extractTypes(input: String): Pair<String, Set<EnrichmentType>?> {
    val idx = input.indexOf("--types", ignoreCase = true)
    if (idx < 0) return input to null
    val command = input.substring(0, idx).trim()
    val typeStr = input.substring(idx + "--types".length).trim()
    val types = typeStr.split(",", " ")
        .filter { it.isNotBlank() }
        .mapNotNull { resolveType(it.trim()) }
        .toSet()
    return command to types.ifEmpty { null }
}

fun resolveType(name: String): EnrichmentType? {
    val normalized = name.uppercase().replace("-", "_")
    return EnrichmentType.entries.firstOrNull { it.name == normalized }
        ?: TYPE_ALIASES[name.lowercase()]
}

val TYPE_ALIASES = mapOf(
    "art" to EnrichmentType.ALBUM_ART,
    "back" to EnrichmentType.ALBUM_ART_BACK,
    "booklet" to EnrichmentType.ALBUM_BOOKLET,
    "cd" to EnrichmentType.CD_ART,
    "bio" to EnrichmentType.ARTIST_BIO,
    "photo" to EnrichmentType.ARTIST_PHOTO,
    "bg" to EnrichmentType.ARTIST_BACKGROUND,
    "logo" to EnrichmentType.ARTIST_LOGO,
    "banner" to EnrichmentType.ARTIST_BANNER,
    "genre" to EnrichmentType.GENRE,
    "label" to EnrichmentType.LABEL,
    "date" to EnrichmentType.RELEASE_DATE,
    "country" to EnrichmentType.COUNTRY,
    "metadata" to EnrichmentType.ALBUM_METADATA,
    "meta" to EnrichmentType.ALBUM_METADATA,
    "tracks" to EnrichmentType.ALBUM_TRACKS,
    "editions" to EnrichmentType.RELEASE_EDITIONS,
    "similar" to EnrichmentType.SIMILAR_ARTISTS,
    "similar-albums" to EnrichmentType.SIMILAR_ALBUMS,
    "similar-tracks" to EnrichmentType.SIMILAR_TRACKS,
    "popularity" to EnrichmentType.ARTIST_POPULARITY,
    "members" to EnrichmentType.BAND_MEMBERS,
    "disco" to EnrichmentType.ARTIST_DISCOGRAPHY,
    "discography" to EnrichmentType.ARTIST_DISCOGRAPHY,
    "links" to EnrichmentType.ARTIST_LINKS,
    "timeline" to EnrichmentType.ARTIST_TIMELINE,
    "radio" to EnrichmentType.ARTIST_RADIO,
    "lyrics" to EnrichmentType.LYRICS_SYNCED,
    "plain-lyrics" to EnrichmentType.LYRICS_PLAIN,
    "credits" to EnrichmentType.CREDITS,
    "discovery" to EnrichmentType.GENRE_DISCOVERY,
    "top" to EnrichmentType.ARTIST_TOP_TRACKS,
    "top-tracks" to EnrichmentType.ARTIST_TOP_TRACKS,
)

/**
 * Batch enrichment — parses semicolon-separated entities and streams results via enrichBatch().
 * Usage: batch artist Radiohead; Pink Floyd; Nirvana
 *        batch album OK Computer by Radiohead; Nevermind by Nirvana
 */
fun executeBatch(input: String, state: DemoState, term: Terminal) {
    val (cleanInput, customTypes) = extractTypes(input)
    val lower = cleanInput.lowercase()

    val kind = when {
        lower.startsWith("artist ") -> "artist"
        lower.startsWith("album ") -> "album"
        lower.startsWith("track ") -> "track"
        else -> {
            term.info("Usage: batch artist|album|track <name1>; <name2>; ...")
            return
        }
    }

    val rest = cleanInput.substring(kind.length + 1).trim()
    val items = rest.split(";").map { it.trim() }.filter { it.isNotBlank() }
    if (items.isEmpty()) {
        term.info("No items to enrich. Separate with semicolons: batch artist A; B; C")
        return
    }

    val requests = items.mapNotNull { item ->
        when (kind) {
            "artist" -> EnrichmentRequest.forArtist(item)
            "album" -> {
                val parts = item.split(BY_REGEX, limit = 2)
                if (parts.size < 2) { term.info("Skipping \"$item\" — use: title by artist"); null }
                else EnrichmentRequest.forAlbum(parts[0].trim(), parts[1].trim())
            }
            "track" -> {
                val parts = item.split(BY_REGEX, limit = 2)
                if (parts.size < 2) { term.info("Skipping \"$item\" — use: title by artist"); null }
                else EnrichmentRequest.forTrack(parts[0].trim(), parts[1].trim())
            }
            else -> null
        }
    }
    if (requests.isEmpty()) return

    val types = customTypes ?: when (kind) {
        "artist" -> ARTIST_TYPES
        "album" -> ALBUM_TYPES
        else -> TRACK_TYPES
    }

    term.heading("Batch ($kind, ${requests.size} items)")
    val startMs = System.currentTimeMillis()
    var totalFound = 0; var totalErrors = 0

    runBlocking {
        state.engine.enrichBatch(requests, types).collect { (request, results) ->
            var found = 0; var notFound = 0; var errors = 0; var stale = 0
            for ((_, result) in results.raw) {
                when (result) {
                    is EnrichmentResult.Success -> { found++; if (result.isStale) stale++ }
                    is EnrichmentResult.NotFound -> notFound++
                    is EnrichmentResult.RateLimited -> errors++
                    is EnrichmentResult.Error -> errors++
                }
            }
            totalFound += found; totalErrors += errors

            val label = when (request) {
                is EnrichmentRequest.ForArtist -> request.name
                is EnrichmentRequest.ForAlbum -> "${request.title} by ${request.artist}"
                is EnrichmentRequest.ForTrack -> "${request.title} by ${request.artist}"
            }

            val parts = mutableListOf("$found found")
            if (notFound > 0) parts += "$notFound not found"
            if (errors > 0) parts += "$errors errors"
            if (stale > 0) parts += "$stale stale"
            val detail = parts.joinToString(", ")

            if (errors > 0) term.warning(label, detail)
            else term.success(label, detail)
        }
    }

    val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
    term.println()
    term.info("${requests.size} items, $totalFound found, $totalErrors errors  [${"%.1f".format(elapsed)}s]")
}
