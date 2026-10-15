package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.Attribution
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
import java.util.Locale

/** Formats enrichment results, profiles, and search results for terminal display. */
object Formatter {

    /** Above this a match is shown in the success colour; below it, as a warning. */
    private const val HIGH_MATCH = 0.90f

    /**
     * A 0.0-1.0 score, two decimals — every score on the library's surface is shown this way, match
     * and affinity alike. `Locale.ROOT` because the separator is part of what a reader compares.
     */
    private fun formatScore(score: Float) = String.format(Locale.ROOT, "%.2f", score)

    /** "5 members: Thom Yorke, Jonny Greenwood, ..." — used by both the profile and result views. */
    private fun membersSummary(members: List<BandMember>) =
        "${members.size} members: ${members.take(4).joinToString(", ") { it.name }}"

    // --- Profile display (Tier 1) ---

    fun printProfile(
        profile: ArtistProfile,
        term: Terminal,
        cacheHits: Int = 0,
        pinned: Set<EnrichmentType> = emptySet(),
    ) {
        printArtistSummary(profile, term)
        printResults(profile.results, term, cacheHits, pinned)
    }

    fun printProfile(
        profile: AlbumProfile,
        term: Terminal,
        cacheHits: Int = 0,
        pinned: Set<EnrichmentType> = emptySet(),
    ) {
        printAlbumSummary(profile, term)
        printResults(profile.results, term, cacheHits, pinned)
    }

    fun printProfile(
        profile: TrackProfile,
        term: Terminal,
        cacheHits: Int = 0,
        pinned: Set<EnrichmentType> = emptySet(),
    ) {
        printTrackSummary(profile, term)
        printResults(profile.results, term, cacheHits, pinned)
    }

    private fun printArtistSummary(profile: ArtistProfile, term: Terminal) {
        term.heading("Profile")
        term.keyValue("Name:", profile.name)
        profile.photo?.let {
            term.keyValue("Photo:", safeLink(it.url, artworkLabel(it), term))
            printCredit(it.attribution, term)
        }
        profile.bio?.let {
            term.keyValue("Bio:", textSnippet(it.text))
            printCredit(it.attribution, term)
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
        profile.artwork?.let {
            term.keyValue("Artwork:", safeLink(it.url, artworkLabel(it), term))
            printCredit(it.attribution, term)
        }
        // The Tier 2 named accessor; AlbumProfile.description reads the same value through Tier 1.
        profile.results.albumDescription()?.let {
            term.keyValue("Description:", textSnippet(it.text))
            printCredit(it.attribution, term)
        }
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
        profile.trackMetadata?.let { meta ->
            meta.albumTitle?.let { term.keyValue("Album:", it) }
            meta.durationMs?.let { term.keyValue("Duration:", clockTime(it)) }
        }
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
        profile.artwork?.let {
            term.keyValue("Artwork:", safeLink(it.url, artworkLabel(it), term))
            printCredit(it.attribution, term)
        }
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

    /** A duration as a clock time — "6:23", the form a track listing is read in. */
    private fun clockTime(ms: Long): String =
        String.format(Locale.ROOT, "%d:%02d", ms / 60_000, (ms % 60_000) / 1000)

    private fun artworkLabel(art: EnrichmentData.Artwork): String {
        val dims = art.sizes?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
            ?.let { s -> s.width?.let { w -> s.height?.let { h -> "${w}x$h" } } }
            ?: art.width?.let { w -> art.height?.let { h -> "${w}x$h" } }
        return dims ?: "image"
    }

    // --- Results display (Tier 2/3) ---

    fun printResults(
        results: EnrichmentResults,
        term: Terminal,
        cacheHits: Int = 0,
        pinned: Set<EnrichmentType> = emptySet(),
    ) {
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
            val pinnedTag = if (type in pinned) " ${term.styled("[pinned]", term.theme.accent)}" else ""
            val tags = "$staleTag$unfilteredTag$pinnedTag"
            if (bestEffort) {
                val unverified = term.styled("[unverified]", term.theme.warning)
                term.warning(typeName(type), "$detail  $conf $unverified$tags")
            } else {
                term.success(typeName(type), "$detail  $conf$tags")
            }
            printResultCredits(result.data, term)
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
                val score = term.styled(formatScore(c.matchScore), term.theme.warning)
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
                formatScore(c.matchScore),
                if (c.matchScore >= HIGH_MATCH) term.theme.success else term.theme.warning,
            )

            val tags = listOfNotNull(c.country, c.releaseType, c.year?.toString())
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
            term.keyValue("Match:", term.styled(formatScore(score), color))
        }
    }

    /** Human-readable type name: ARTIST_BIO -> "Artist Bio" */
    internal fun typeName(type: EnrichmentType): String =
        type.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun genreSnippet(data: EnrichmentData.Metadata): String? =
        data.genreTags?.take(3)?.joinToString(", ") { "${it.name}(%.2f)".format(it.confidence) }
            ?: data.genres?.take(4)?.joinToString(", ")

    /**
     * A terminal hyperlink only for an http(s) URL on a styled terminal with no whitespace, control,
     * format (bidi, zero-width) or line-separator character and no userinfo, which a terminal can
     * render as a different host; otherwise the label followed by the escaped URL. The URL is
     * printed either way; this only decides whether it is clickable.
     */
    private fun safeLink(url: String, label: String, term: Terminal): String {
        val clickable = Regex("https?://[^/@]*(?:/.*)?", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .matches(url) && url.none { it.isWhitespace() || needsEscape(it) }
        val styled = term.theme.reset.isNotEmpty()
        return if (clickable && styled) term.link(url, label) else "$label ${escapeControls(url)}"
    }

    private fun needsEscape(c: Char): Boolean =
        c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() || c == '\u2028' || c == '\u2029'

    /**
     * Printable text: each control (C0, DEL, C1), format (bidi, zero-width) and line-separator
     * character becomes a visible `\uXXXX` escape, so none can reorder or hide terminal output.
     */
    internal fun escapeControls(text: String): String = buildString {
        for (c in text) {
            if (needsEscape(c)) append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
    }

    private fun printResultCredits(data: EnrichmentData, term: Terminal) {
        when (data) {
            is EnrichmentData.Artwork -> {
                printCredit(data.attribution, term)
                data.alternatives.orEmpty().forEach { printCredit(it.attribution, term, it.provider) }
            }
            is EnrichmentData.Biography -> printCredit(data.attribution, term)
            else -> {}
        }
    }

    /**
     * One indented row per attribution fact the upstream stated, as plain text. Nothing here
     * decides whether content is shown: the content row is already printed, and every fact that is
     * present is printed, including contradictory, restrictive and unlinkable ones.
     */
    private fun printCredit(attribution: Attribution?, term: Terminal, owner: String? = null) {
        if (attribution == null) return
        val prefix = "      " + (owner?.let { "${escapeControls(it)} " } ?: "")
        val facts = listOf(
            "title" to attribution.title?.let { t -> attribution.language?.let { "$t ($it)" } ?: t },
            "source" to attribution.sourceUrl,
            "creator" to attribution.creator,
            "attribution" to attribution.attributionText,
            "credit" to attribution.credit,
            "licence" to attribution.licence,
            "licence url" to attribution.licenceUrl,
            "other licences" to attribution.otherLicences.takeIf { it.isNotEmpty() }?.joinToString(", "),
            "copyright" to attribution.copyrightStatus,
            "modification" to attribution.modification,
            "restrictions" to attribution.restrictions.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        for ((label, value) in facts) {
            if (value == null) continue
            term.println(term.styled("$prefix$label: ${escapeControls(value)}", term.theme.muted))
        }
    }

    private fun artworkSnippet(data: EnrichmentData.Artwork, provider: String, term: Terminal): String {
        val label = artworkLabel(data).let { if (it != "image") "$provider $it" else provider }
        val primary = safeLink(data.url, label, term)
        val alts = data.alternatives
        if (alts.isNullOrEmpty()) return primary
        val altLinks = alts.joinToString(", ") { safeLink(it.url, it.provider, term) }
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
        is EnrichmentData.Biography -> textSnippet(data.text)
        is EnrichmentData.SimilarArtists ->
            "${data.artists.size} artists: " +
                data.artists.take(3).joinToString(", ") {
                    val named = it.disambiguation?.let { text -> "${it.name} — $text" } ?: it.name
                    "$named (rank ${formatScore(it.matchScore)})"
                }
        is EnrichmentData.Popularity -> buildString {
            data.listenerCount?.let { append("listeners=$it ") }
            data.listenCount?.let { append("plays=$it ") }
        }
        is EnrichmentData.BandMembers -> membersSummary(data.members)
        is EnrichmentData.Discography -> "${data.albums.size} albums"
        is EnrichmentData.Tracklist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarTracks ->
            data.tracks.take(3).joinToString(", ") {
                "${it.title} — ${it.artist} (rank ${formatScore(it.matchScore)})"
            }
        is EnrichmentData.ArtistLinks -> data.links.take(3).joinToString(", ") { it.label ?: it.type }
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
            "${data.albums.size} albums: " + data.albums.take(3).joinToString(", ") {
                "${it.title} by ${it.artist}" + (it.year?.let { year -> " ($year)" } ?: "")
            }
        is EnrichmentData.GenreDiscovery ->
            "${data.relatedGenres.size} genres: " +
                data.relatedGenres.take(3).joinToString(", ") { "${it.name} (affinity ${formatScore(it.affinity)})" }
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
