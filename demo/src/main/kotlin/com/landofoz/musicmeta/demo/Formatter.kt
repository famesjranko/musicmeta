package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TrackProfile
import com.landofoz.musicmeta.demo.ui.Terminal

/** Formats enrichment results, profiles, and search results for terminal display. */
object Formatter {

    // --- Profile display (Tier 1) ---

    fun printProfile(profile: ArtistProfile, term: Terminal, cacheHits: Int = 0) {
        printArtistSummary(profile, term)
        printResults(profile.results, term, cacheHits)
    }

    fun printProfile(profile: AlbumProfile, term: Terminal, cacheHits: Int = 0) {
        printAlbumSummary(profile, term)
        printResults(profile.results, term, cacheHits)
    }

    fun printProfile(profile: TrackProfile, term: Terminal, cacheHits: Int = 0) {
        printTrackSummary(profile, term)
        printResults(profile.results, term, cacheHits)
    }

    private fun printArtistSummary(profile: ArtistProfile, term: Terminal) {
        term.heading("Profile")
        term.keyValue("Name:", profile.name)
        profile.photo?.let { term.keyValue("Photo:", term.link(it.url, artworkLabel(it))) }
        profile.bio?.let {
            val snippet = it.text.replace(Regex("<[^>]*>"), "").trim().take(80)
            term.keyValue("Bio:", "\"$snippet...\"")
        }
        val genres = profile.genres.take(4).joinToString(", ") { it.name }
        if (genres.isNotEmpty()) term.keyValue("Genres:", genres)
        profile.country?.let { term.keyValue("Country:", it) }
        if (profile.members.isNotEmpty()) term.keyValue("Members:", "${profile.members.size} members: ${profile.members.take(4).joinToString(", ") { it.name }}")
        profile.popularity?.let { p ->
            p.listenerCount?.let { term.keyValue("Listeners:", "%,d".format(it)) }
        }
        term.println()
    }

    private fun printAlbumSummary(profile: AlbumProfile, term: Terminal) {
        term.heading("Profile")
        term.keyValue("Title:", profile.title)
        term.keyValue("Artist:", profile.artist)
        profile.artwork?.let { term.keyValue("Artwork:", term.link(it.url, artworkLabel(it))) }
        profile.label?.let { term.keyValue("Label:", it) }
        profile.releaseDate?.let { term.keyValue("Released:", it) }
        val genres = profile.genres.take(4).joinToString(", ") { it.name }
        if (genres.isNotEmpty()) term.keyValue("Genres:", genres)
        profile.country?.let { term.keyValue("Country:", it) }
        if (profile.tracks.isNotEmpty()) term.keyValue("Tracks:", "${profile.tracks.size} tracks")
        term.println()
    }

    private fun printTrackSummary(profile: TrackProfile, term: Terminal) {
        term.heading("Profile")
        term.keyValue("Title:", profile.title)
        term.keyValue("Artist:", profile.artist)
        val genres = profile.genres.take(4).joinToString(", ") { it.name }
        if (genres.isNotEmpty()) term.keyValue("Genres:", genres)
        profile.lyrics?.let { l ->
            val desc = buildString {
                if (l.isInstrumental) append("[instrumental]")
                else {
                    l.syncedLyrics?.let { append("synced, ${it.lines().size} lines") }
                        ?: l.plainLyrics?.let { append("plain, ${it.lines().size} lines") }
                }
            }
            if (desc.isNotEmpty()) term.keyValue("Lyrics:", desc)
        }
        profile.artwork?.let { term.keyValue("Artwork:", term.link(it.url, artworkLabel(it))) }
        profile.popularity?.let { p ->
            p.listenerCount?.let { term.keyValue("Listeners:", "%,d".format(it)) }
        }
        term.println()
    }

    private fun artworkLabel(art: EnrichmentData.Artwork): String {
        val dims = art.sizes?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
            ?.let { s -> s.width?.let { w -> s.height?.let { h -> "${w}x${h}" } } }
            ?: art.width?.let { w -> art.height?.let { h -> "${w}x${h}" } }
        return dims ?: "image"
    }

    // --- Results display (Tier 2/3) ---

    fun printResults(results: EnrichmentResults, term: Terminal, cacheHits: Int = 0) {
        printIdentity(results, term)
        term.println()

        var found = 0; var notFound = 0; var errors = 0; var timedOut = 0

        val (successes, rest) = results.raw.entries.partition { it.value is EnrichmentResult.Success }

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
            val staleTag = if (result.isStale) " ${term.styled("[stale]", term.theme.warning)}" else ""
            if (result.identityMatch == IdentityMatch.BEST_EFFORT) {
                term.warning(typeName(type), "$detail  $conf ${term.styled("[unverified]", term.theme.warning)}$staleTag")
            } else {
                term.success(typeName(type), "$detail  $conf$staleTag")
            }
        }

        if (rest.isNotEmpty() && successes.isNotEmpty()) term.println()
        for ((type, result) in rest) {
            when (result) {
                is EnrichmentResult.NotFound -> { notFound++; term.missing(typeName(type), "") }
                is EnrichmentResult.RateLimited -> { errors++; term.warning(typeName(type), "rate limited") }
                is EnrichmentResult.Error -> {
                    if (result.errorKind == ErrorKind.TIMEOUT) { timedOut++; term.warning(typeName(type), "timed out") }
                    else { errors++; term.error(typeName(type), "${result.errorKind}: ${result.message.take(50)}") }
                }
                is EnrichmentResult.Success -> {}
            }
        }

        term.summary(found, notFound, errors, cached = cacheHits, timedOut = timedOut)

        val suggestions = results.identity?.suggestions
        if (!suggestions.isNullOrEmpty()) {
            term.println()
            term.warning("Did you mean?", "Identity match below threshold")
            suggestions.forEachIndexed { i, c ->
                val name = term.styled(c.title, term.theme.bold)
                val artist = c.artist?.let { " by $it" } ?: ""
                val score = term.styled("${c.score}%", term.theme.warning)
                val disambig = c.disambiguation?.let { " ${term.styled("($it)", term.theme.muted)}" } ?: ""
                term.println("    ${i + 1}. $name$artist  $score$disambig")
            }
            term.info("Use 'pick <number>' to enrich by MBID.")
        }
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

            val tags = listOfNotNull(c.country, c.releaseType, c.year?.take(4))
            val tagStr = if (tags.isNotEmpty()) term.styled(tags.joinToString(" ${term.theme.dot} "), term.theme.muted) else ""
            term.println("  $num $name$artist  $score  $tagStr")

            c.disambiguation?.let { term.println("     ${term.styled(it, term.theme.muted)}") }
        }
        term.println()
        term.info("Use 'pick <number>' to enrich a specific result.")
    }

    private fun printIdentity(results: EnrichmentResults, term: Terminal) {
        val resolution = results.identity ?: return
        val ids = resolution.identifiers

        val hasAny = ids.musicBrainzId != null || ids.wikidataId != null || ids.wikipediaTitle != null
        if (!hasAny) return

        term.heading("Identity")
        ids.musicBrainzId?.let { term.keyValue("MBID:", it) }
        ids.wikidataId?.let { term.keyValue("Wikidata:", it) }
        ids.wikipediaTitle?.let { term.keyValue("Wikipedia:", it) }
        resolution.matchScore?.let { score ->
            val color = if (score >= 90) term.theme.success else term.theme.warning
            term.keyValue("Match:", term.styled("$score%", color))
        }
    }

    /** Human-readable type name: ARTIST_BIO -> "Artist Bio" */
    internal fun typeName(type: EnrichmentType): String =
        type.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun artworkSnippet(data: EnrichmentData.Artwork, provider: String, term: Terminal): String {
        val label = artworkLabel(data).let { if (it != "image") "$provider $it" else provider }
        val primary = term.link(data.url, label)
        val alts = data.alternatives
        if (alts.isNullOrEmpty()) return primary
        val altLinks = alts.joinToString(", ") { term.link(it.url, it.provider) }
        return "$primary (+${alts.size} alt: $altLinks)"
    }

    private fun snippet(data: EnrichmentData): String = when (data) {
        is EnrichmentData.Artwork -> data.url.take(70) + if (data.url.length > 70) "..." else ""
        is EnrichmentData.Metadata -> listOfNotNull(
            data.genreTags?.let { tags -> tags.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.confidence) } }
                ?: data.genres?.take(4)?.joinToString(", "),
            data.label, data.releaseDate, data.releaseType, data.country,
        ).joinToString(" | ")
        is EnrichmentData.Lyrics -> buildString {
            if (data.isInstrumental) append("[instrumental] ")
            data.syncedLyrics?.let { append("synced=${it.lines().size} lines ") }
            data.plainLyrics?.let { append("plain=${it.lines().size} lines") }
        }
        is EnrichmentData.Biography -> "\"${data.text.replace(Regex("<[^>]*>"), "").trim().take(80)}...\""
        is EnrichmentData.SimilarArtists ->
            "${data.artists.size} artists: " + data.artists.take(3).joinToString(", ") { "${it.name}(%.1f)".format(it.matchScore) }
        is EnrichmentData.Popularity -> buildString {
            data.listenerCount?.let { append("listeners=$it ") }
            data.listenCount?.let { append("plays=$it ") }
            data.topTracks?.firstOrNull()?.let { append("top: ${it.title}") }
        }
        is EnrichmentData.BandMembers -> "${data.members.size} members: ${data.members.take(4).joinToString(", ") { it.name }}"
        is EnrichmentData.Discography -> "${data.albums.size} albums"
        is EnrichmentData.Tracklist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarTracks -> data.tracks.take(3).joinToString(", ") { "${it.title}(%.1f)".format(it.matchScore) }
        is EnrichmentData.ArtistLinks -> data.links.take(3).joinToString(", ") { it.type }
        is EnrichmentData.Credits -> {
            val cats = data.credits.groupBy { it.roleCategory ?: "other" }
            cats.entries.joinToString(", ") { "${it.value.size} ${it.key}" }
        }
        is EnrichmentData.ReleaseEditions ->
            "${data.editions.size} editions" + data.editions.mapNotNull { it.format }.distinct().take(3)
                .let { if (it.isNotEmpty()) " (${it.joinToString(", ")})" else "" }
        is EnrichmentData.ArtistTimeline -> "${data.events.size} events"
        is EnrichmentData.RadioPlaylist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarAlbums ->
            "${data.albums.size} albums: " + data.albums.take(3).joinToString(", ") { "${it.title} by ${it.artist}" }
        is EnrichmentData.GenreDiscovery ->
            "${data.relatedGenres.size} genres: " + data.relatedGenres.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.affinity) }
        is EnrichmentData.TopTracks ->
            "${data.tracks.size} tracks: " + data.tracks.take(3).joinToString(", ") {
                val plays = it.listenCount?.let { c -> " (${c})" } ?: ""
                "${it.title}$plays"
            }
    }
}
