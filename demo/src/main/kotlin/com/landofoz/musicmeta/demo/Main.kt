package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.demo.ui.Spinner
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val theme = Theme.detect()
    val term = Terminal(theme)
    val spinner = Spinner(term)

    term.banner("musicmeta CLI")

    val engine = buildEngine()
    Formatter.printProviders(engine.getProviders(), term)
    term.println()

    if (args.isNotEmpty()) {
        runSingleCommand(args, engine, term, spinner)
    } else {
        Formatter.printHelp(term)
        term.println()
        repl(engine, term, spinner)
    }
}

private fun buildEngine(): EnrichmentEngine {
    val secrets = loadSecrets()
    val keys = ApiKeyConfig(
        lastFmKey = secrets["lastfm.apikey"] ?: env("LASTFM_API_KEY"),
        fanartTvProjectKey = secrets["fanarttv.apikey"] ?: env("FANARTTV_API_KEY"),
        discogsPersonalToken = secrets["discogs.token"] ?: env("DISCOGS_TOKEN"),
    )
    return EnrichmentEngine.Builder()
        .apiKeys(keys)
        .withDefaultProviders()
        .build()
}

private fun repl(engine: EnrichmentEngine, term: Terminal, spinner: Spinner) {
    while (true) {
        val line = term.prompt() ?: break
        val trimmed = line.trim()
        if (trimmed.isBlank()) continue

        when {
            trimmed.equals("quit", ignoreCase = true) ||
                trimmed.equals("exit", ignoreCase = true) -> break
            trimmed.equals("help", ignoreCase = true) -> Formatter.printHelp(term)
            trimmed.equals("providers", ignoreCase = true) ->
                Formatter.printProviders(engine.getProviders(), term)
            else -> executeCommand(trimmed, engine, term, spinner)
        }
        term.println()
    }
}

private fun executeCommand(input: String, engine: EnrichmentEngine, term: Terminal, spinner: Spinner) {
    val command = parseCommand(input)
    if (command == null) {
        term.info("Unknown command. Type 'help' for usage.")
        return
    }

    runBlocking {
        when (command) {
            is Command.Enrich -> {
                val (label, types) = when (command.request) {
                    is EnrichmentRequest.ForArtist -> "Enriching artist \"${command.request.name}\"" to ARTIST_TYPES
                    is EnrichmentRequest.ForAlbum -> "Enriching album \"${command.request.title}\" by \"${command.request.artist}\"" to ALBUM_TYPES
                    is EnrichmentRequest.ForTrack -> "Enriching track \"${command.request.title}\" by \"${command.request.artist}\"" to TRACK_TYPES
                }
                val results = spinner.spin("$label...") {
                    engine.enrich(command.request, types)
                }
                Formatter.printResults(results, term)
            }
            is Command.Search -> {
                val results = spinner.spin("Searching...") {
                    engine.search(command.request, limit = 10)
                }
                Formatter.printSearchResults(results, term)
            }
        }
    }
}

private fun runSingleCommand(args: Array<String>, engine: EnrichmentEngine, term: Terminal, spinner: Spinner) {
    val input = args.joinToString(" ")
    executeCommand(input, engine, term, spinner)
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

/** Load API keys from secrets.properties (project root), if it exists. */
private fun loadSecrets(): Map<String, String> {
    val paths = listOf(
        java.io.File("secrets.properties"),       // running from demo/
        java.io.File("../secrets.properties"),     // running from project root
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
    EnrichmentType.ARTIST_POPULARITY, EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.BAND_MEMBERS, EnrichmentType.ARTIST_DISCOGRAPHY,
    EnrichmentType.ARTIST_LINKS, EnrichmentType.ARTIST_TIMELINE,
    EnrichmentType.ARTIST_RADIO, EnrichmentType.GENRE_DISCOVERY,
)

private val ALBUM_TYPES = setOf(
    EnrichmentType.ALBUM_ART, EnrichmentType.GENRE, EnrichmentType.LABEL,
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
