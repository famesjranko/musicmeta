package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.demo.ui.Terminal

/** Formats help text, config, providers, cache stats, and catalog — the static info commands. */
object InfoFormatter {

    fun printHelp(term: Terminal) {
        val col = 38 // align descriptions at this column
        fun cmd(name: String, args: String, desc: String) {
            val left = "  ${term.styled(name, term.theme.bold)} $args"
            val rawLen = name.length + args.length + 3
            term.println("$left${" ".repeat(maxOf(col - rawLen, 2))}${term.styled(desc, term.theme.muted)}")
        }

        term.heading("Enrich & Search")
        cmd("artist", "<name>", "Enrich an artist")
        cmd("album", "<title> by <artist>", "Enrich an album")
        cmd("track", "<title> by <artist>", "Enrich a track")
        term.println("${" ".repeat(4)}${term.styled("Add --types bio,art,... to select specific types", term.theme.muted)}")
        cmd("search", "artist|album|track ...", "Search for candidates")
        cmd("pick", "<number>", "Enrich a search result by MBID")

        term.heading("Cache Management")
        cmd("refresh", "artist|album|track ...", "Re-enrich bypassing cache")
        cmd("invalidate", "artist|album|track ...", "Clear cached data")
        cmd("cache", "[clear]", "View cache stats or clear all")

        term.heading("Engine")
        cmd("config", "[key value]", "View or set configuration")
        cmd("verbose", "", "Toggle verbose logging")
        cmd("catalog", "[add|remove|mode ...]", "Manage demo catalog")
        cmd("providers", "[detail]", "Show providers & capabilities")
        cmd("help", "", "Show this help")
        cmd("quit", "", "Exit")
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

    fun printProviders(providers: List<ProviderInfo>, term: Terminal) {
        val active = providers.count { it.isAvailable }
        term.println()
        term.info("Providers ($active/${providers.size} active):")

        val sorted = providers.sortedByDescending { it.isAvailable }
        val maxName = sorted.maxOf { it.displayName.length }
        val colWidth = maxName + 10 // name + " ACTIVE" + padding
        for (i in sorted.indices step 2) {
            term.providerRow(sorted[i].displayName, sorted[i].isAvailable, colWidth)
            if (i + 1 < sorted.size) {
                term.providerRow(sorted[i + 1].displayName, sorted[i + 1].isAvailable, colWidth)
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
            term.println("  ${term.styled(p.displayName.padEnd(18), term.theme.bold)} $status")

            val types = p.capabilities.sortedByDescending { it.priority }
                .joinToString("  ") { "${Formatter.typeName(it.type).lowercase()}(${it.priority})" }
            if (types.isNotEmpty()) term.println("    ${term.styled(types, term.theme.muted)}")
        }
    }

    fun printCacheStats(cache: TrackingCache, term: Terminal) {
        term.heading("Cache")
        val total = cache.hits + cache.misses
        val rate = if (total > 0) "%.0f%%".format(cache.hits.toFloat() / total * 100) else "-"
        term.keyValue("Hits:", "${cache.hits}")
        term.keyValue("Misses:", "${cache.misses}")
        term.keyValue("Hit Rate:", rate)
        term.println()
        term.info("Use 'refresh <entity>' to bypass cache, 'invalidate <entity>' to clear.")
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
}
