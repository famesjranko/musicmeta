package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ApiKey
import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.RadioDiscoveryMode
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.albumProfile
import com.landofoz.musicmeta.artistProfile
import com.landofoz.musicmeta.demo.ui.Spinner
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import com.landofoz.musicmeta.okhttp.OkHttpEnrichmentClient
import com.landofoz.musicmeta.trackProfile
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

fun main(args: Array<String>) {
    val theme = Theme.detect()
    val term = Terminal(theme)
    val spinner = Spinner(term)

    term.banner("musicmeta demo CLI")

    val state = DemoState(logger = DemoLogger(term))
    state.rebuild()
    InfoFormatter.printProviders(state.engine.getProviders(), term)
    term.println()

    if (args.isNotEmpty()) {
        runSingleCommand(args, state, term, spinner)
    } else {
        InfoFormatter.printHelp(term)
        term.println()
        repl(state, term, spinner)
    }
    state.engine.close()
}

/** Mutable engine state — rebuilt when config, catalog, or verbose settings change. */
class DemoState(
    // The demo hits the live APIs, so it carries the contact information MusicBrainz and Wikimedia
    // require, rather than the contactless default.
    var config: EnrichmentConfig = EnrichmentConfig(
        userAgent = "musicmeta-cli-demo/1.0 (+https://github.com/famesjranko/musicmeta)",
    ),
    val cache: TrackingCache = TrackingCache(),
    val logger: DemoLogger,
    val catalog: DemoCatalog = DemoCatalog(),
    var catalogMode: CatalogFilterMode = CatalogFilterMode.UNFILTERED,
    var httpBackend: HttpBackend = HttpBackend.DEFAULT,
    var radioMode: RadioDiscoveryMode = RadioDiscoveryMode.EASY,
) {
    lateinit var engine: EnrichmentEngine

    /** Last search results — used by 'pick' command for disambiguation flow. */
    var lastSearchResults: List<SearchCandidate> = emptyList()
    var lastSearchType: String? = null // "artist", "album", "track"
    private val secrets = loadSecrets()

    fun rebuild() {
        // The engine being replaced is closed rather than abandoned: close() releases the scope a
        // detached enrichProgressive fan-out would still be running on.
        if (::engine.isInitialized) engine.close()

        val keys = ApiKeyConfig.of(
            *listOfNotNull(
                (secrets["lastfm.apikey"] ?: env("LASTFM_API_KEY"))?.let { ApiKey.LASTFM_API_KEY to it },
                (secrets["fanarttv.apikey"] ?: env("FANARTTV_API_KEY"))?.let { ApiKey.FANARTTV_PROJECT_KEY to it },
                (secrets["discogs.token"] ?: env("DISCOGS_TOKEN"))?.let { ApiKey.DISCOGS_PERSONAL_TOKEN to it },
                (secrets["listenbrainz.token"] ?: env("LISTENBRAINZ_TOKEN"))
                    ?.let { ApiKey.LISTENBRAINZ_USER_TOKEN to it },
            ).toTypedArray(),
        )
        val baseConfig = config.copy(radioDiscoveryMode = radioMode)

        val builder = EnrichmentEngine.Builder()
            .apiKeys(keys)
            .config(baseConfig)
            .cache(cache)
            .logger(logger)

        // After config(), which replaces the builder's config wholesale — catalog() copies onto it.
        if (catalogMode != CatalogFilterMode.UNFILTERED) {
            builder.catalog(catalog, catalogMode)
        }

        if (httpBackend == HttpBackend.OKHTTP) {
            builder.httpClient(OkHttpEnrichmentClient(OkHttpClient(), baseConfig.userAgent))
        }

        engine = builder.withDefaultProviders().build()
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
            trimmed.equals("help", ignoreCase = true) -> InfoFormatter.printHelp(term)
            trimmed.equals("providers detail", ignoreCase = true) ->
                InfoFormatter.printProviderDetail(state.engine.getProviders(), term)
            trimmed.equals("providers", ignoreCase = true) ->
                InfoFormatter.printProviders(state.engine.getProviders(), term)
            trimmed.equals("config", ignoreCase = true) ->
                InfoFormatter.printConfig(
                    state.config,
                    state.logger.enabled,
                    state.catalogMode,
                    state.httpBackend,
                    state.radioMode,
                    term,
                )
            trimmed.startsWith("config ", ignoreCase = true) ->
                handleConfig(trimmed.substringAfter("config ").trim(), state, term)
            trimmed.equals("verbose", ignoreCase = true) -> toggleVerbose(state, term)
            trimmed.equals("cache", ignoreCase = true) -> InfoFormatter.printCacheStats(state.cache, term)
            trimmed.startsWith("cache ", ignoreCase = true) ->
                handleCache(trimmed.substringAfter("cache ").trim(), state, term)
            trimmed.equals("catalog", ignoreCase = true) ->
                InfoFormatter.printCatalog(state.catalog, state.catalogMode, term)
            trimmed.startsWith("catalog ", ignoreCase = true) ->
                handleCatalog(trimmed.substringAfter("catalog ").trim(), state, term)
            trimmed.startsWith("refresh ", ignoreCase = true) ->
                executeRefresh(trimmed.substringAfter("refresh ").trim(), state, term, spinner)
            trimmed.startsWith("invalidate ", ignoreCase = true) ->
                handleInvalidate(trimmed.substringAfter("invalidate ").trim(), state, term)
            trimmed.startsWith("stream ", ignoreCase = true) ->
                executeStream(trimmed.substringAfter("stream ").trim(), state, term)
            trimmed.startsWith("pin ", ignoreCase = true) ->
                handlePin(trimmed.substringAfter("pin ").trim(), state, term)
            trimmed.startsWith("pick ", ignoreCase = true) ->
                pickCandidate(trimmed.substringAfter("pick ").trim(), state, term, spinner)
            trimmed.startsWith("batch ", ignoreCase = true) ->
                executeBatch(trimmed.substringAfter("batch ").trim(), state, term)
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
                val types = selectTypes(customTypes, entityKind(command.request), term)
                    ?: return@runBlocking
                val label = enrichLabel(command.request)
                val hitsBefore = state.cache.hits
                val profile = if (state.logger.enabled) {
                    term.info(label)
                    enrichProfile(state, command.request, types)
                } else {
                    spinner.spin("$label...") { enrichProfile(state, command.request, types) }
                }
                val cacheHits = state.cache.hits - hitsBefore
                val pinned = pinnedTypes(state, command.request, types)
                printEnrichedProfile(profile, command.request, term, cacheHits, pinned)

                if (profile.suggestions.isNotEmpty()) {
                    state.lastSearchResults = profile.suggestions
                    state.lastSearchType = entityKind(command.request)
                }
            }
            is Command.Search -> {
                val results = spinner.spin("Searching...") {
                    state.engine.search(command.request, limit = 10)
                }
                state.lastSearchResults = results
                state.lastSearchType = entityKind(command.request)
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
    val kind = state.lastSearchType ?: "artist"
    val types = selectTypes(customTypes, kind, term) ?: return
    val disambig = candidate.disambiguation?.let { " ($it)" } ?: ""
    val label = "Enriching ${state.lastSearchType} \"${candidate.title}\"$disambig"
    runBlocking {
        val hitsBefore = state.cache.hits
        val profile = if (state.logger.enabled) {
            term.info(label)
            enrichFromCandidate(state, candidate, state.lastSearchType, types)
        } else {
            spinner.spin("$label...") { enrichFromCandidate(state, candidate, state.lastSearchType, types) }
        }
        val cacheHits = state.cache.hits - hitsBefore
        when (profile) {
            is EnrichedProfile.Artist -> Formatter.printProfile(profile.value, term, cacheHits)
            is EnrichedProfile.Album -> Formatter.printProfile(profile.value, term, cacheHits)
            is EnrichedProfile.Track -> Formatter.printProfile(profile.value, term, cacheHits)
        }
    }
}

/** Uses SearchCandidate overloads — the Tier 1 "did you mean?" → re-enrich flow. */
private suspend fun enrichFromCandidate(
    state: DemoState,
    candidate: SearchCandidate,
    searchType: String?,
    types: Set<EnrichmentType>,
): EnrichedProfile = when (searchType) {
    "artist" -> EnrichedProfile.Artist(state.engine.artistProfile(candidate, types))
    "album" -> EnrichedProfile.Album(state.engine.albumProfile(candidate, types))
    "track" -> EnrichedProfile.Track(state.engine.trackProfile(candidate, types = types))
    else -> EnrichedProfile.Artist(state.engine.artistProfile(candidate, types))
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
            if (name.isBlank()) null else Command.Search(EnrichmentRequest.forArtist(name))
        }
        lower.startsWith("album ") -> {
            val parts = rest.substringAfter("album ").trim().split(BY_REGEX, limit = 2)
            if (parts.isEmpty() || parts[0].isBlank()) null
            else Command.Search(EnrichmentRequest.forAlbum(parts[0].trim(), parts.getOrNull(1)?.trim() ?: ""))
        }
        lower.startsWith("track ") -> {
            val parts = rest.substringAfter("track ").trim().split(BY_REGEX, limit = 2)
            if (parts.isEmpty() || parts[0].isBlank()) null
            else Command.Search(EnrichmentRequest.forTrack(parts[0].trim(), parts.getOrNull(1)?.trim() ?: ""))
        }
        else -> null
    }
}

private fun enrichLabel(request: EnrichmentRequest): String = when (request) {
    is EnrichmentRequest.ForArtist -> "Enriching artist \"${request.name}\""
    is EnrichmentRequest.ForAlbum -> "Enriching album \"${request.title}\" by \"${request.artist}\""
    is EnrichmentRequest.ForTrack -> "Enriching track \"${request.title}\" by \"${request.artist}\""
}

private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

/**
 * Where a demo run out of [demoDir] looks for `secrets.properties`, outermost first: the main
 * checkout's file when [demoDir] sits in a git worktree, then the repo root's, then the demo's own.
 * [loadSecrets] merges in this order, so the nearer file wins per key.
 */
internal fun secretsSearchPath(demoDir: File): List<File> {
    val repoRoot = File(demoDir, "..")
    return listOfNotNull(mainCheckoutSecrets(repoRoot)) +
        listOf(File(repoRoot, "secrets.properties"), File(demoDir, "secrets.properties"))
}

/**
 * Every `secrets.properties` on [secretsSearchPath] merged, the nearer file winning per key — not
 * the first that exists, which cannot tell a template from a configuration (`docs/pitfalls.md` §13).
 *
 * A key present but blank is dropped rather than returned as `""`: every caller reads
 * `secrets[k] ?: env(k)`, and an empty string is non-null, so it would beat the environment variable
 * it is meant to defer to.
 */
internal fun loadSecrets(demoDir: File = File(".")): Map<String, String> =
    secretsSearchPath(demoDir)
        .filter { it.exists() }
        .fold(mutableMapOf<String, String>()) { merged, file -> merged.apply { putAll(file.readKeys()) } }

/**
 * The main checkout's `secrets.properties`, when [repoRoot] is a git worktree — a worktree
 * materialises only tracked files, so a gitignored secrets file exists solely in the main
 * checkout and a worktree run is silently keyless without this (`docs/pitfalls.md` §13). A
 * worktree's `.git` is a *file* naming its real git dir (`gitdir: <main>/.git/worktrees/<name>`);
 * anything else — a `.git` directory, or a file this cannot parse — contributes nothing. Joins
 * the search path outermost, so both nearer files still win per key.
 */
internal fun mainCheckoutSecrets(repoRoot: File): File? {
    val gitMarker = File(repoRoot, ".git")
    if (!gitMarker.isFile) return null
    val gitdir = gitMarker.readLines().firstOrNull { it.startsWith("gitdir:") }
        ?.removePrefix("gitdir:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolved = File(gitdir).let { if (it.isAbsolute) it else File(repoRoot, gitdir) }
    // A worktree's git dir is <main>/.git/worktrees/<name>; any other shape (a submodule's
    // .git/modules/<name>, say) is not a worktree and names no main checkout.
    val worktrees = resolved.parentFile?.takeIf { it.name == "worktrees" } ?: return null
    val dotGit = worktrees.parentFile?.takeIf { it.name == ".git" } ?: return null
    val main = dotGit.parentFile ?: return null
    return File(main, "secrets.properties")
}

private fun File.readKeys(): Map<String, String> = readLines()
    .filter { it.contains('=') && !it.trimStart().startsWith('#') }
    .associate { line -> val (k, v) = line.split('=', limit = 2); k.trim() to v.trim() }
    .filterValues { it.isNotEmpty() }

private sealed class Command {
    data class Enrich(val request: EnrichmentRequest) : Command()
    data class Search(val request: EnrichmentRequest) : Command()
}
