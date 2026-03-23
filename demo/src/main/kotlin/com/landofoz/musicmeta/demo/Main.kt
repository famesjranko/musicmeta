package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.demo.ui.Spinner
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val theme = Theme.detect()
    val term = Terminal(theme)
    val spinner = Spinner(term)

    term.banner("musicmeta CLI")

    val state = DemoState(logger = DemoLogger(term))
    state.rebuild()
    Formatter.printProviders(state.engine.getProviders(), term)
    term.println()

    if (args.isNotEmpty()) {
        runSingleCommand(args, state, term, spinner)
    } else {
        Formatter.printHelp(term)
        term.println()
        repl(state, term, spinner)
    }
}

/** Mutable engine state — rebuilt when config, catalog, or verbose settings change. */
class DemoState(
    var config: EnrichmentConfig = EnrichmentConfig(),
    val cache: TrackingCache = TrackingCache(),
    val logger: DemoLogger,
    val catalog: DemoCatalog = DemoCatalog(),
    var catalogMode: CatalogFilterMode = CatalogFilterMode.UNFILTERED,
) {
    lateinit var engine: EnrichmentEngine
    /** Last search results — used by 'pick' command for disambiguation flow. */
    var lastSearchResults: List<SearchCandidate> = emptyList()
    var lastSearchType: String? = null // "artist", "album", "track"
    private val secrets = loadSecrets()

    fun rebuild() {
        val keys = ApiKeyConfig(
            lastFmKey = secrets["lastfm.apikey"] ?: env("LASTFM_API_KEY"),
            fanartTvProjectKey = secrets["fanarttv.apikey"] ?: env("FANARTTV_API_KEY"),
            discogsPersonalToken = secrets["discogs.token"] ?: env("DISCOGS_TOKEN"),
        )
        val effectiveConfig = if (catalogMode != CatalogFilterMode.UNFILTERED) {
            config.copy(catalogProvider = catalog, catalogFilterMode = catalogMode)
        } else config

        engine = EnrichmentEngine.Builder()
            .apiKeys(keys)
            .config(effectiveConfig)
            .cache(cache)
            .logger(logger)
            .withDefaultProviders()
            .build()
    }
}

private fun repl(state: DemoState, term: Terminal, spinner: Spinner) {
    while (true) {
        val line = term.prompt() ?: break
        val trimmed = line.trim()
        if (trimmed.isBlank()) continue

        when {
            trimmed.equals("quit", ignoreCase = true) ||
                trimmed.equals("exit", ignoreCase = true) -> break
            trimmed.equals("help", ignoreCase = true) -> Formatter.printHelp(term)
            trimmed.equals("providers detail", ignoreCase = true) ->
                Formatter.printProviderDetail(state.engine.getProviders(), term)
            trimmed.equals("providers", ignoreCase = true) ->
                Formatter.printProviders(state.engine.getProviders(), term)
            trimmed.equals("config", ignoreCase = true) ->
                Formatter.printConfig(state.config, state.logger.enabled, state.catalogMode, term)
            trimmed.startsWith("config ", ignoreCase = true) ->
                handleConfig(trimmed.substringAfter("config ").trim(), state, term)
            trimmed.equals("verbose", ignoreCase = true) -> toggleVerbose(state, term)
            trimmed.equals("cache", ignoreCase = true) -> Formatter.printCacheStats(state.cache, term)
            trimmed.startsWith("cache ", ignoreCase = true) ->
                handleCache(trimmed.substringAfter("cache ").trim(), state, term)
            trimmed.equals("catalog", ignoreCase = true) ->
                Formatter.printCatalog(state.catalog, state.catalogMode, term)
            trimmed.startsWith("catalog ", ignoreCase = true) ->
                handleCatalog(trimmed.substringAfter("catalog ").trim(), state, term)
            trimmed.startsWith("pick ", ignoreCase = true) ->
                pickCandidate(trimmed.substringAfter("pick ").trim(), state, term, spinner)
            else -> executeCommand(trimmed, state, term, spinner)
        }
        term.println()
    }
}

private fun executeCommand(input: String, state: DemoState, term: Terminal, spinner: Spinner) {
    val (cleanInput, customTypes) = extractTypes(input)
    val command = parseCommand(cleanInput)
    if (command == null) {
        term.info("Unknown command. Type 'help' for usage.")
        return
    }

    runBlocking {
        when (command) {
            is Command.Enrich -> {
                val (label, defaultTypes) = when (command.request) {
                    is EnrichmentRequest.ForArtist ->
                        "Enriching artist \"${command.request.name}\"" to ARTIST_TYPES
                    is EnrichmentRequest.ForAlbum ->
                        "Enriching album \"${command.request.title}\" by \"${command.request.artist}\"" to ALBUM_TYPES
                    is EnrichmentRequest.ForTrack ->
                        "Enriching track \"${command.request.title}\" by \"${command.request.artist}\"" to TRACK_TYPES
                }
                val types = customTypes ?: defaultTypes
                val hitsBefore = state.cache.hits
                val results = if (state.logger.enabled) {
                    term.info(label)
                    state.engine.enrich(command.request, types)
                } else {
                    spinner.spin("$label...") { state.engine.enrich(command.request, types) }
                }
                val cacheHits = state.cache.hits - hitsBefore
                Formatter.printResults(results, term, cacheHits)
                // Store suggestions so 'pick' works from "Did you mean?" prompts
                val suggestions = results.values
                    .filterIsInstance<EnrichmentResult.NotFound>()
                    .firstOrNull { it.suggestions != null }
                    ?.suggestions
                if (suggestions != null) {
                    state.lastSearchResults = suggestions
                    state.lastSearchType = when (command.request) {
                        is EnrichmentRequest.ForArtist -> "artist"
                        is EnrichmentRequest.ForAlbum -> "album"
                        is EnrichmentRequest.ForTrack -> "track"
                    }
                }
            }
            is Command.Search -> {
                val results = spinner.spin("Searching...") {
                    state.engine.search(command.request, limit = 10)
                }
                state.lastSearchResults = results
                state.lastSearchType = when (command.request) {
                    is EnrichmentRequest.ForArtist -> "artist"
                    is EnrichmentRequest.ForAlbum -> "album"
                    is EnrichmentRequest.ForTrack -> "track"
                }
                Formatter.printSearchResults(results, term)
            }
        }
    }
}

private fun pickCandidate(input: String, state: DemoState, term: Terminal, spinner: Spinner) {
    val (indexStr, customTypes) = extractTypes(input)
    val index = indexStr.toIntOrNull()
    if (index == null || index < 1 || index > state.lastSearchResults.size) {
        if (state.lastSearchResults.isEmpty()) {
            term.info("No search results. Run 'search artist <name>' first.")
        } else {
            term.info("Pick a number between 1 and ${state.lastSearchResults.size}.")
        }
        return
    }
    val candidate = state.lastSearchResults[index - 1]
    val mbid = candidate.identifiers.musicBrainzId
    val request = when (state.lastSearchType) {
        "artist" -> EnrichmentRequest.forArtist(candidate.title, mbid = mbid)
        "album" -> EnrichmentRequest.forAlbum(
            candidate.title, candidate.artist ?: "", mbid = mbid,
        )
        else -> {
            term.info("Pick is only supported for artist and album searches.")
            return
        }
    }
    val types = customTypes ?: when (state.lastSearchType) {
        "artist" -> ARTIST_TYPES
        "album" -> ALBUM_TYPES
        else -> TRACK_TYPES
    }
    val disambig = candidate.disambiguation?.let { " ($it)" } ?: ""
    val label = "Enriching ${state.lastSearchType} \"${candidate.title}\"$disambig"
    runBlocking {
        val hitsBefore = state.cache.hits
        val results = if (state.logger.enabled) {
            term.info(label)
            state.engine.enrich(request, types)
        } else {
            spinner.spin("$label...") { state.engine.enrich(request, types) }
        }
        val cacheHits = state.cache.hits - hitsBefore
        Formatter.printResults(results, term, cacheHits)
    }
}

private fun runSingleCommand(args: Array<String>, state: DemoState, term: Terminal, spinner: Spinner) {
    executeCommand(args.joinToString(" "), state, term, spinner)
}

private fun parseCommand(input: String): Command? {
    val lower = input.lowercase()
    return when {
        lower.startsWith("artist ") -> {
            val name = input.substringAfter("artist ").trim()
            if (name.isBlank()) null
            else Command.Enrich(EnrichmentRequest.forArtist(name))
        }
        lower.startsWith("album ") -> {
            val rest = input.substringAfter("album ").trim()
            val parts = rest.split(BY_REGEX, limit = 2)
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else Command.Enrich(EnrichmentRequest.forAlbum(parts[0].trim(), parts[1].trim()))
        }
        lower.startsWith("track ") -> {
            val rest = input.substringAfter("track ").trim()
            val parts = rest.split(BY_REGEX, limit = 2)
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else Command.Enrich(EnrichmentRequest.forTrack(parts[0].trim(), parts[1].trim()))
        }
        lower.startsWith("search ") -> parseSearchCommand(input.substringAfter("search ").trim())
        else -> null
    }
}

private fun parseSearchCommand(rest: String): Command? {
    val lower = rest.lowercase()
    return when {
        lower.startsWith("artist ") -> {
            val name = rest.substringAfter("artist ").trim()
            if (name.isBlank()) null
            else Command.Search(EnrichmentRequest.forArtist(name))
        }
        lower.startsWith("album ") -> {
            val parts = rest.substringAfter("album ").trim().split(BY_REGEX, limit = 2)
            if (parts.isEmpty() || parts[0].isBlank()) null
            else Command.Search(EnrichmentRequest.forAlbum(
                parts[0].trim(),
                parts.getOrNull(1)?.trim() ?: "",
            ))
        }
        lower.startsWith("track ") -> {
            val parts = rest.substringAfter("track ").trim().split(BY_REGEX, limit = 2)
            if (parts.isEmpty() || parts[0].isBlank()) null
            else Command.Search(EnrichmentRequest.forTrack(
                parts[0].trim(),
                parts.getOrNull(1)?.trim() ?: "",
            ))
        }
        else -> null
    }
}

private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

private fun loadSecrets(): Map<String, String> {
    val paths = listOf(
        java.io.File("secrets.properties"),
        java.io.File("../secrets.properties"),
    )
    val file = paths.firstOrNull { it.exists() } ?: return emptyMap()
    return file.readLines()
        .filter { it.contains('=') && !it.trimStart().startsWith('#') }
        .associate { line ->
            val (k, v) = line.split('=', limit = 2)
            k.trim() to v.trim()
        }
}

private val ARTIST_TYPES = setOf(
    EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO,
    EnrichmentType.ARTIST_BACKGROUND, EnrichmentType.ARTIST_LOGO,
    EnrichmentType.ARTIST_BANNER, EnrichmentType.ARTIST_POPULARITY,
    EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.BAND_MEMBERS,
    EnrichmentType.ARTIST_DISCOGRAPHY, EnrichmentType.ARTIST_LINKS,
    EnrichmentType.ARTIST_TIMELINE, EnrichmentType.ARTIST_RADIO,
    EnrichmentType.ARTIST_TOP_TRACKS, EnrichmentType.GENRE_DISCOVERY,
)

private val ALBUM_TYPES = setOf(
    EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_ART_BACK,
    EnrichmentType.ALBUM_BOOKLET, EnrichmentType.CD_ART,
    EnrichmentType.GENRE, EnrichmentType.LABEL,
    EnrichmentType.RELEASE_DATE, EnrichmentType.RELEASE_TYPE, EnrichmentType.COUNTRY,
    EnrichmentType.ALBUM_METADATA, EnrichmentType.ALBUM_TRACKS,
    EnrichmentType.RELEASE_EDITIONS, EnrichmentType.SIMILAR_ALBUMS,
    EnrichmentType.GENRE_DISCOVERY,
)

private val TRACK_TYPES = setOf(
    EnrichmentType.GENRE, EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN,
    EnrichmentType.TRACK_POPULARITY, EnrichmentType.SIMILAR_TRACKS,
    EnrichmentType.CREDITS, EnrichmentType.GENRE_DISCOVERY,
)

private val BY_REGEX = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE)

private sealed class Command {
    data class Enrich(val request: EnrichmentRequest) : Command()
    data class Search(val request: EnrichmentRequest) : Command()
}
