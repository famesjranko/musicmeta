package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.demo.ui.Terminal

/** Formats enrichment results, search results, and provider info for terminal display. */
object Formatter {

    fun printResults(results: Map<EnrichmentType, EnrichmentResult>, term: Terminal) {
        printIdentity(results, term)
        term.println()

        var found = 0; var skipped = 0; var errors = 0

        // Show found results first, then missing
        val (successes, rest) = results.entries.partition { it.value is EnrichmentResult.Success }

        term.heading("Results")
        for ((type, result) in successes) {
            result as EnrichmentResult.Success
            found++
            val provider = result.provider.take(17).padEnd(17)
            val conf = "%.2f".format(result.confidence)
            term.success(type.name, "$provider $conf  ${snippet(result.data)}")
        }

        for ((type, result) in rest) {
            when (result) {
                is EnrichmentResult.NotFound -> {
                    skipped++
                    term.missing(type.name, result.provider)
                }
                is EnrichmentResult.RateLimited -> {
                    errors++
                    term.warning(type.name, "rate limited (${result.provider})")
                }
                is EnrichmentResult.Error -> {
                    errors++
                    term.error(type.name, "${result.provider}: ${result.message.take(50)}")
                }
                is EnrichmentResult.Success -> {} // already handled
            }
        }

        term.summary(found, skipped, errors)
    }

    fun printSearchResults(candidates: List<SearchCandidate>, term: Terminal) {
        if (candidates.isEmpty()) {
            term.info("No candidates found.")
            return
        }
        term.heading("Search Results")
        candidates.forEachIndexed { i, c ->
            val artist = c.artist?.let { " by $it" } ?: ""
            val year = c.year ?: "-"
            val country = c.country ?: "-"
            val type = c.releaseType ?: "-"
            val score = c.score
            term.success(
                "${i + 1}. ${c.title}$artist".take(40),
                "$country  $type  $year  score=$score",
            )
        }
    }

    fun printProviders(providers: List<ProviderInfo>, term: Terminal) {
        val active = providers.count { it.isAvailable }
        term.println()
        term.info("Providers ($active/${providers.size} active):")

        // Print in 2-column grid
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

    fun printHelp(term: Terminal) {
        term.heading("Commands")
        term.println("  ${term.styled("artist", term.theme.bold)} <name>                Enrich an artist")
        term.println("  ${term.styled("album", term.theme.bold)} <title> by <artist>    Enrich an album")
        term.println("  ${term.styled("track", term.theme.bold)} <title> by <artist>    Enrich a track")
        term.println("  ${term.styled("search", term.theme.bold)} artist <name>         Search artist candidates")
        term.println("  ${term.styled("search", term.theme.bold)} album <title> <artist> Search album candidates")
        term.println("  ${term.styled("providers", term.theme.bold)}                      Show provider status")
        term.println("  ${term.styled("help", term.theme.bold)}                           Show this help")
        term.println("  ${term.styled("quit", term.theme.bold)}                           Exit")
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

    /** One-line summary of enrichment data. Exhaustive over all EnrichmentData subtypes. */
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
                    .let { if (it.isNotEmpty()) " (${ it.joinToString(", ")})" else "" }
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
