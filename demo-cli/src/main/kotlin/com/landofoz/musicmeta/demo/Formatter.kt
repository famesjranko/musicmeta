package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.BandMember
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TrackProfile
import com.landofoz.musicmeta.demo.ui.Terminal

/** Formats enrichment results, profiles, and search results for terminal display. */
object Formatter {

    /** Above this a match is shown in the success colour; below it, as a warning. */
    private const val HIGH_MATCH = 0.90f

    /** A 0.0-1.0 match score, two decimals, as every score on the library's surface carries it. */
    private fun formatMatchScore(score: Float) = "%.2f".format(score)

    /** "5 members: Thom Yorke, Jonny Greenwood, ..." — used by both the profile and result views. */
    private fun membersSummary(members: List<BandMember>) =
        "${members.size} members: ${members.take(4).joinToString(", ") { it.name }}"

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
        if (profile.members.isNotEmpty()) term.keyValue("Members:", membersSummary(profile.members))
        profile.popularity?.let { p ->
            p.listenerCount?.let { term.keyValue("Listeners:", "%,d".format(it)) }
        }
        profile.radioDiscovery?.let { term.keyValue("LB Radio:", "${it.tracks.size} tracks") }
        term.println()
    }

    private fun printAlbumSummary(profile: AlbumProfile, term: Terminal) {
        term.heading("Profile")
        term.keyValue("Title:", profile.title)
        term.keyValue("Artist:", profile.artist)
        profile.artwork?.let { term.keyValue("Artwork:", term.link(it.url, artworkLabel(it))) }
        // The Tier 2 named accessor; AlbumProfile.description reads the same value through Tier 1.
        profile.results.albumDescription()?.let { term.keyValue("Description:", textSnippet(it.text)) }
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
        profile.preview?.let {
            val label = it.source + (it.durationMs?.let { ms -> " ${ms / 1000}s" } ?: "")
            term.keyValue("Preview:", term.link(it.url, label))
        }
        profile.artwork?.let { term.keyValue("Artwork:", term.link(it.url, artworkLabel(it))) }
        profile.popularity?.let { p ->
            p.listenerCount?.let { term.keyValue("Listeners:", "%,d".format(it)) }
        }
        term.println()
    }

    /** Quoted first [max] characters of prose, with an ellipsis only when something was cut. */
    private fun textSnippet(text: String, max: Int = 80): String {
        val plain = text.replace(Regex("<[^>]*>"), "").trim()
        return "\"${plain.take(max)}${if (plain.length > max) "..." else ""}\""
    }

    private fun artworkLabel(art: EnrichmentData.Artwork): String {
        val dims = art.sizes?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
            ?.let { s -> s.width?.let { w -> s.height?.let { h -> "${w}x$h" } } }
            ?: art.width?.let { w -> art.height?.let { h -> "${w}x$h" } }
        return dims ?: "image"
    }

    // --- Results display (Tier 2/3) ---

    fun printResults(results: EnrichmentResults, term: Terminal, cacheHits: Int = 0) {
        printIdentity(results, term)
        term.println()

        var found = 0; var notFound = 0; var errors = 0; var timedOut = 0

        val (successes, rest) = results.raw.entries.partition { it.value is EnrichmentResult.Success }
        // A canonical status that never confirmed the entity means every Success this call
        // produced is a fuzzy or ambiguous guess, whatever LookupProvenance the individual result
        // carries.
        val bestEffort = results.identity.status in
            setOf(CanonicalStatus.AMBIGUOUS, CanonicalStatus.UNRESOLVED, CanonicalStatus.FAILED)

        term.heading("Results")
        for ((type, result) in successes) {
            result as EnrichmentResult.Success
            found++
            val conf = term.styled("%.0f%%".format(result.confidence * 100), term.theme.muted)
            val detail = if (result.data is EnrichmentData.Artwork) {
                artworkSnippet(result.data as EnrichmentData.Artwork, result.provider, term)
            } else {
                snippet(type, result.data)
            }.ifBlank { term.styled("(no value for this field)", term.theme.muted) }
            val staleTag = if (result.isStale) " ${term.styled("[stale]", term.theme.warning)}" else ""
            val unfilteredTag =
                if (result.isCatalogDegraded) " ${term.styled("[unranked]", term.theme.warning)}" else ""
            if (bestEffort) {
                val unverified = term.styled("[unverified]", term.theme.warning)
                term.warning(typeName(type), "$detail  $conf $unverified$staleTag$unfilteredTag")
            } else {
                term.success(typeName(type), "$detail  $conf$staleTag$unfilteredTag")
            }
        }

        if (rest.isNotEmpty() && successes.isNotEmpty()) term.println()
        for ((type, result) in rest) {
            when (result) {
                is EnrichmentResult.NotFound -> { notFound++; term.missing(typeName(type), "") }
                is EnrichmentResult.RateLimited -> { errors++; term.warning(typeName(type), "rate limited") }
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

        val suggestions = results.identity.suggestions
        if (suggestions.isNotEmpty()) {
            term.println()
            term.warning("Did you mean?", "Identity match below threshold")
            suggestions.forEachIndexed { i, c ->
                val name = term.styled(c.title, term.theme.bold)
                val artist = c.artist?.let { " by $it" } ?: ""
                val score = term.styled(formatMatchScore(c.matchScore), term.theme.warning)
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
            val score = term.styled(
                formatMatchScore(c.matchScore),
                if (c.matchScore >= HIGH_MATCH) term.theme.success else term.theme.warning,
            )

            val tags = listOfNotNull(c.country, c.releaseType, c.year?.take(4))
            val tagStr = if (tags.isEmpty()) {
                ""
            } else {
                term.styled(tags.joinToString(" ${term.theme.dot} "), term.theme.muted)
            }
            term.println("  $num $name$artist  $score  $tagStr")

            c.disambiguation?.let { term.println("     ${term.styled(it, term.theme.muted)}") }
        }
        term.println()
        term.info("Use 'pick <number>' to enrich a specific result.")
    }

    private fun printIdentity(results: EnrichmentResults, term: Terminal) {
        val resolution = results.identity
        val ids = resolution.identifiers

        val hasAny = ids.musicBrainzId != null || ids.wikidataId != null || ids.wikipediaTitle != null
        if (!hasAny) return

        term.heading("Identity")
        ids.musicBrainzId?.let { term.keyValue("MBID:", it) }
        ids.wikidataId?.let { term.keyValue("Wikidata:", it) }
        ids.wikipediaTitle?.let { term.keyValue("Wikipedia:", it) }
        resolution.matchScore?.let { score ->
            val color = if (score >= HIGH_MATCH) term.theme.success else term.theme.warning
            term.keyValue("Match:", term.styled(formatMatchScore(score), color))
        }
    }

    /** Human-readable type name: ARTIST_BIO -> "Artist Bio" */
    internal fun typeName(type: EnrichmentType): String =
        type.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun genreSnippet(data: EnrichmentData.Metadata): String? =
        data.genreTags?.take(3)?.joinToString(", ") { "${it.name}(%.2f)".format(it.confidence) }
            ?: data.genres?.take(4)?.joinToString(", ")

    private fun artworkSnippet(data: EnrichmentData.Artwork, provider: String, term: Terminal): String {
        val label = artworkLabel(data).let { if (it != "image") "$provider $it" else provider }
        val primary = term.link(data.url, label)
        val alts = data.alternatives
        if (alts.isNullOrEmpty()) return primary
        val altLinks = alts.joinToString(", ") { term.link(it.url, it.provider) }
        return "$primary (+${alts.size} alt: $altLinks)"
    }

    /** One Metadata/Lyrics payload answers several types; each row shows only the field it names. */
    private fun snippet(type: EnrichmentType, data: EnrichmentData): String = when (data) {
        is EnrichmentData.Artwork -> data.url.take(70) + if (data.url.length > 70) "..." else ""
        is EnrichmentData.Metadata -> when (type) {
            EnrichmentType.GENRE -> genreSnippet(data)
            EnrichmentType.LABEL -> data.label
            EnrichmentType.RELEASE_DATE -> data.releaseDate
            EnrichmentType.RELEASE_TYPE -> data.releaseType
            EnrichmentType.COUNTRY -> data.country
            else -> listOfNotNull(
                genreSnippet(data), data.label, data.releaseDate, data.releaseType, data.country,
            ).joinToString(" | ")
        }.orEmpty()
        is EnrichmentData.Lyrics -> {
            val synced = data.syncedLyrics?.let { "synced=${it.lines().size} lines" }
            val plain = data.plainLyrics?.let { "plain=${it.lines().size} lines" }
            // LRCLIB answers a synced request with plain-only lyrics on purpose; show what came
            // back, labelled, rather than blanking the row.
            val (own, sibling) =
                if (type == EnrichmentType.LYRICS_PLAIN) plain to synced else synced to plain
            listOfNotNull(
                "[instrumental]".takeIf { data.isInstrumental },
                own ?: sibling?.let { "$it (fallback)" },
            ).joinToString(" ")
        }
        is EnrichmentData.Biography -> "\"${data.text.replace(Regex("<[^>]*>"), "").trim().take(80)}...\""
        is EnrichmentData.SimilarArtists ->
            "${data.artists.size} artists: " +
                data.artists.take(3).joinToString(", ") { "${it.name}(%.1f)".format(it.matchScore) }
        is EnrichmentData.Popularity -> buildString {
            data.listenerCount?.let { append("listeners=$it ") }
            data.listenCount?.let { append("plays=$it ") }
        }
        is EnrichmentData.BandMembers -> membersSummary(data.members)
        is EnrichmentData.Discography -> "${data.albums.size} albums"
        is EnrichmentData.Tracklist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarTracks ->
            data.tracks.take(3).joinToString(", ") { "${it.title}(%.1f)".format(it.matchScore) }
        is EnrichmentData.ArtistLinks -> data.links.take(3).joinToString(", ") { it.type }
        is EnrichmentData.Credits -> {
            val cats = data.credits.groupBy { it.roleCategory ?: "other" }
            cats.entries.joinToString(", ") { "${it.value.size} ${it.key}" }
        }
        is EnrichmentData.ReleaseEditions ->
            "${data.editions.size} editions" + data.editions.mapNotNull { it.format }.distinct().take(3)
                .let { if (it.isNotEmpty()) " (${it.joinToString(", ")})" else "" }
        is EnrichmentData.ArtistTimeline -> "${data.events.size} events"
        is EnrichmentData.TrackPreview ->
            "${data.source} " + (data.durationMs?.let { "${it / 1000}s " } ?: "") + "preview"
        is EnrichmentData.RadioPlaylist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarAlbums ->
            "${data.albums.size} albums: " + data.albums.take(3).joinToString(", ") { "${it.title} by ${it.artist}" }
        is EnrichmentData.GenreDiscovery ->
            "${data.relatedGenres.size} genres: " +
                data.relatedGenres.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.affinity) }
        is EnrichmentData.TopTracks ->
            "${data.tracks.size} tracks: " + data.tracks.take(3).joinToString(", ") {
                val plays = it.listenCount?.let { c -> " ($c)" } ?: ""
                "${it.title}$plays"
            }
        is EnrichmentData.TrackMetadata -> listOfNotNull(
            data.durationMs?.let { "${it / 1000}s" },
            data.albumTitle,
            data.disambiguation,
        ).joinToString(" | ")
    }
}
