package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.demo.ui.Terminal

/** Formats enrichment results, search results, and provider info for terminal display. */
object Formatter {

    fun printResults(results: Map<EnrichmentType, EnrichmentResult>, term: Terminal, cacheHits: Int = 0) {
        printIdentity(results, term)
        term.println()

        var found = 0; var notFound = 0; var errors = 0; var timedOut = 0

        val (successes, rest) = results.entries.partition { it.value is EnrichmentResult.Success }

        term.heading("Results")
        for ((type, result) in successes) {
            result as EnrichmentResult.Success
            found++
            val conf = term.styled("%.0f%%".format(result.confidence * 100), term.theme.muted)
            val detail = if (result.data is EnrichmentData.Artwork) {
                artworkSnippet(result.data as EnrichmentData.Artwork, result.provider, term)
            } else {
                snippet(result.data)
            }
            term.success(typeName(type), "$detail  $conf")
        }

        if (rest.isNotEmpty() && successes.isNotEmpty()) term.println()
        for ((type, result) in rest) {
            when (result) {
                is EnrichmentResult.NotFound -> {
                    notFound++
                    term.missing(typeName(type), "")
                }
                is EnrichmentResult.RateLimited -> {
                    errors++
                    term.warning(typeName(type), "rate limited")
                }
                is EnrichmentResult.Error -> {
                    if (result.errorKind == ErrorKind.TIMEOUT) {
                        timedOut++
                        term.warning(typeName(type), "timed out")
                    } else {
                        errors++
                        term.error(typeName(type), "${result.errorKind}: ${result.message.take(50)}")
                    }
                }
                is EnrichmentResult.Success -> {}
            }
        }

        term.summary(found, notFound, errors, cached = cacheHits, timedOut = timedOut)
    }

    fun printSearchResults(candidates: List<SearchCandidate>, term: Terminal) {
        if (candidates.isEmpty()) {
            term.info("No candidates found.")
            return
        }
        term.heading("Search Results")
        candidates.forEachIndexed { i, c ->
            val num = term.styled("${i + 1}.", term.theme.bold)
            val name = term.styled(c.title, term.theme.bold)
            val artist = c.artist?.let { " by $it" } ?: ""
            val score = term.styled("${c.score}%", if (c.score >= 90) term.theme.success else term.theme.warning)

            // Line 1: number, name, score
            val tags = listOfNotNull(
                c.country,
                c.releaseType,
                c.year?.take(4),
            )
            val tagStr = if (tags.isNotEmpty()) term.styled(tags.joinToString(" ${term.theme.dot} "), term.theme.muted) else ""
            term.println("  $num $name$artist  $score  $tagStr")

            // Line 2: disambiguation (if present)
            c.disambiguation?.let {
                term.println("     ${term.styled(it, term.theme.muted)}")
            }
        }
        term.println()
        term.info("Use 'pick <number>' to enrich a specific result.")
    }

    fun printProviders(providers: List<ProviderInfo>, term: Terminal) {
        val active = providers.count { it.isAvailable }
        term.println()
        term.info("Providers ($active/${providers.size} active):")

        val sorted = providers.sortedByDescending { it.isAvailable }
        for (i in sorted.indices step 2) {
            term.providerRow(sorted[i].displayName, sorted[i].isAvailable)
            if (i + 1 < sorted.size) {
                term.providerRow(sorted[i + 1].displayName, sorted[i + 1].isAvailable)
            }
            term.println()
        }

        val missing = providers.filter { !it.isAvailable }
        if (missing.isNotEmpty()) {
            term.println()
            term.info("Set LASTFM_API_KEY, FANARTTV_API_KEY, DISCOGS_TOKEN for full coverage.")
        }
    }

    fun printProviderDetail(providers: List<ProviderInfo>, term: Terminal) {
        term.heading("Provider Capabilities")
        for (p in providers.sortedByDescending { it.isAvailable }) {
            val status = if (p.isAvailable) term.styled("ACTIVE", term.theme.success)
            else term.styled("NO KEY", term.theme.warning)
            val identity = if (p.capabilities.isEmpty()) "" else ""
            term.println("  ${term.styled(p.displayName.padEnd(18), term.theme.bold)} $status $identity")

            val types = p.capabilities.sortedByDescending { it.priority }
                .joinToString("  ") { "${typeName(it.type).lowercase()}(${it.priority})" }
            if (types.isNotEmpty()) term.println("    ${term.styled(types, term.theme.muted)}")
        }
    }

    fun printConfig(config: EnrichmentConfig, verbose: Boolean, catalogMode: CatalogFilterMode, term: Terminal) {
        term.heading("Configuration")
        term.keyValue("Timeout:", "${config.enrichTimeoutMs}ms")
        term.keyValue("Confidence:", "%.2f".format(config.minConfidence))
        term.keyValue("Identity:", if (config.enableIdentityResolution) "on" else "off")
        term.keyValue("Verbose:", if (verbose) "on" else "off")
        val modeName = when (catalogMode) {
            CatalogFilterMode.UNFILTERED -> "off"
            CatalogFilterMode.AVAILABLE_ONLY -> "available only"
            CatalogFilterMode.AVAILABLE_FIRST -> "available first"
        }
        term.keyValue("Catalog:", modeName)
        if (config.confidenceOverrides.isNotEmpty()) {
            term.keyValue("Overrides:",
                config.confidenceOverrides.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
    }

    fun printCatalog(catalog: DemoCatalog, mode: CatalogFilterMode, term: Terminal) {
        val modeName = when (mode) {
            CatalogFilterMode.UNFILTERED -> "off"
            CatalogFilterMode.AVAILABLE_ONLY -> "available only"
            CatalogFilterMode.AVAILABLE_FIRST -> "available first"
        }
        term.heading("Demo Catalog ($modeName)")
        if (catalog.artists.isEmpty()) {
            term.info("Empty. Use 'catalog add <artist>' to add artists.")
        } else {
            for (artist in catalog.artists.sorted()) {
                term.println("  ${term.styled(term.theme.bullet, term.theme.accent)} $artist")
            }
        }
        term.println()
        term.info("Recommendations are filtered against this catalog.")
        term.info("Try: catalog add Radiohead, then enrich an artist with similar-artists.")
    }

    fun printCacheStats(cache: TrackingCache, term: Terminal) {
        term.heading("Cache")
        val total = cache.hits + cache.misses
        val rate = if (total > 0) "%.0f%%".format(cache.hits.toFloat() / total * 100) else "-"
        term.keyValue("Hits:", "${cache.hits}")
        term.keyValue("Misses:", "${cache.misses}")
        term.keyValue("Hit Rate:", rate)
    }

    fun printHelp(term: Terminal) {
        term.heading("Enrich & Search")
        fun cmd(name: String, args: String, desc: String) {
            val left = "  ${term.styled(name, term.theme.bold)} $args"
            val rawLen = name.length + args.length + 3
            term.println("$left${" ".repeat(maxOf(32 - rawLen, 2))}${term.styled(desc, term.theme.muted)}")
        }
        cmd("artist", "<name>", "Enrich an artist")
        cmd("album", "<title> by <artist>", "Enrich an album")
        cmd("track", "<title> by <artist>", "Enrich a track")
        term.println("${" ".repeat(4)}${term.styled("Add --types bio,art,... to select specific types", term.theme.muted)}")
        cmd("search", "artist|album|track ...", "Search for candidates")
        cmd("pick", "<number>", "Enrich a search result by its MBID")

        term.heading("Engine")
        cmd("config", "[key value]", "View or set configuration")
        cmd("verbose", "", "Toggle verbose logging")
        cmd("cache", "[clear]", "View cache stats or clear")
        cmd("catalog", "[add|remove|mode ...]", "Manage demo catalog")
        cmd("providers", "[detail]", "Show providers & capabilities")
        cmd("help", "", "Show this help")
        cmd("quit", "", "Exit")
    }

    private fun printIdentity(results: Map<EnrichmentType, EnrichmentResult>, term: Terminal) {
        val identity = results.values
            .filterIsInstance<EnrichmentResult.Success>()
            .firstOrNull { it.resolvedIdentifiers != null }
            ?.resolvedIdentifiers ?: return

        val hasAny = identity.musicBrainzId != null ||
            identity.wikidataId != null ||
            identity.wikipediaTitle != null
        if (!hasAny) return

        term.heading("Identity")
        identity.musicBrainzId?.let { term.keyValue("MBID:", it) }
        identity.wikidataId?.let { term.keyValue("Wikidata:", it) }
        identity.wikipediaTitle?.let { term.keyValue("Wikipedia:", it) }
    }

    /** Human-readable type name: ARTIST_BIO -> "Artist Bio" */
    internal fun typeName(type: EnrichmentType): String =
        type.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** Artwork-specific display with clickable terminal hyperlinks. */
    private fun artworkSnippet(data: EnrichmentData.Artwork, provider: String, term: Terminal): String {
        val dims = data.sizes?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
            ?.let { s -> s.width?.let { w -> s.height?.let { h -> "${w}x${h}" } } }
            ?: data.width?.let { w -> data.height?.let { h -> "${w}x${h}" } }
        val label = if (dims != null) "$provider $dims" else provider
        val primary = term.link(data.url, label)
        val alts = data.alternatives
        if (alts.isNullOrEmpty()) return primary
        val altLinks = alts.joinToString(", ") { term.link(it.url, it.provider) }
        return "$primary (+${alts.size} alt: $altLinks)"
    }

    /** One-line summary of enrichment data. */
    private fun snippet(data: EnrichmentData): String = when (data) {
        is EnrichmentData.Artwork ->
            data.url.take(70) + if (data.url.length > 70) "..." else ""
        is EnrichmentData.Metadata -> listOfNotNull(
            data.genreTags?.let { tags ->
                tags.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.confidence) }
            } ?: data.genres?.take(4)?.joinToString(", "),
            data.label, data.releaseDate, data.releaseType, data.country,
        ).joinToString(" | ")
        is EnrichmentData.Lyrics -> buildString {
            if (data.isInstrumental) append("[instrumental] ")
            data.syncedLyrics?.let { append("synced=${it.lines().size} lines ") }
            data.plainLyrics?.let { append("plain=${it.lines().size} lines") }
        }
        is EnrichmentData.Biography ->
            "\"${data.text.take(80)}...\""
        is EnrichmentData.SimilarArtists ->
            "${data.artists.size} artists: " +
                data.artists.take(3).joinToString(", ") { "${it.name}(%.1f)".format(it.matchScore) }
        is EnrichmentData.Popularity -> buildString {
            data.listenerCount?.let { append("listeners=$it ") }
            data.listenCount?.let { append("plays=$it ") }
            data.topTracks?.firstOrNull()?.let { append("top: ${it.title}") }
        }
        is EnrichmentData.BandMembers ->
            "${data.members.size} members: ${data.members.take(4).joinToString(", ") { it.name }}"
        is EnrichmentData.Discography -> "${data.albums.size} albums"
        is EnrichmentData.Tracklist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarTracks ->
            data.tracks.take(3).joinToString(", ") { "${it.title}(%.1f)".format(it.matchScore) }
        is EnrichmentData.ArtistLinks ->
            data.links.take(3).joinToString(", ") { it.type }
        is EnrichmentData.Credits -> {
            val cats = data.credits.groupBy { it.roleCategory ?: "other" }
            cats.entries.joinToString(", ") { "${it.value.size} ${it.key}" }
        }
        is EnrichmentData.ReleaseEditions ->
            "${data.editions.size} editions" +
                data.editions.mapNotNull { it.format }.distinct().take(3)
                    .let { if (it.isNotEmpty()) " (${it.joinToString(", ")})" else "" }
        is EnrichmentData.ArtistTimeline ->
            "${data.events.size} events"
        is EnrichmentData.RadioPlaylist ->
            "${data.tracks.size} tracks"
        is EnrichmentData.SimilarAlbums ->
            "${data.albums.size} albums: " +
                data.albums.take(3).joinToString(", ") { "${it.title} by ${it.artist}" }
        is EnrichmentData.GenreDiscovery ->
            "${data.relatedGenres.size} genres: " +
                data.relatedGenres.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.affinity) }
    }
}
