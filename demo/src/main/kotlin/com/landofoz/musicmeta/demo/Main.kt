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
    val keys = ApiKeyConfig(
        lastFmKey = env("LASTFM_API_KEY"),
        fanartTvProjectKey = env("FANARTTV_API_KEY"),
        discogsPersonalToken = env("DISCOGS_TOKEN"),
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
                val label = when (command.request) {
                    is EnrichmentRequest.ForArtist -> "Enriching artist \"${command.request.name}\""
                    is EnrichmentRequest.ForAlbum -> "Enriching album \"${command.request.title}\" by \"${command.request.artist}\""
                    is EnrichmentRequest.ForTrack -> "Enriching track \"${command.request.title}\" by \"${command.request.artist}\""
                }
                val results = spinner.spin("$label...") {
                    engine.enrich(command.request, ALL_TYPES)
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

private val ALL_TYPES = EnrichmentType.entries.toSet()
private val BY_REGEX = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE)

private sealed class Command {
    data class Enrich(val request: EnrichmentRequest) : Command()
    data class Search(val request: EnrichmentRequest) : Command()
}
