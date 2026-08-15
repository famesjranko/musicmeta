package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreAffinity
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TrackProfile

fun ArtistProfile.toDemoResponse(elapsedMs: Long): DemoResponse {
    val r = results
    val bio = r.biography()
    val stats = r.artistPopularity()

    val details = buildList {
        r.country()?.let { add(SectionItem("Country", it)) }
    }

    val sections = buildList {
        r.identity.suggestions.let { s ->
            didYouMeanSection(s) { artistEnrich(it.title) }?.let { add(it) }
        }
        if (details.isNotEmpty()) add(Section("details", "Details", details))
        section("similar_artists", "Similar Artists") {
            r.similarArtists()?.artists?.map {
                SectionItem(
                    primary = it.name,
                    secondary = "match ${(it.matchScore * 100).toInt()}%",
                    meta = it.sources.joinToString(", ").ifBlank { null },
                    enrich = artistEnrich(it.name),
                )
            }
        }
        section("top_tracks", "Top Tracks") {
            r.topTracks()?.tracks?.sortedBy { it.rank }?.map {
                val ids = it.identifiers.toWireIdentifiers()
                SectionItem(
                    primary = it.title,
                    secondary = it.album,
                    meta = "#${it.rank}",
                    previewTitle = it.title,
                    previewArtist = name,
                    previewAlbum = it.album,
                    identifiers = ids,
                    enrich = trackEnrich(it.title, name, it.album, ids),
                )
            }
        }
        section("radio", "Radio") { radioItems(r.radio()) }
        section("radio_discovery", "Radio Discovery") {
            radioItems(r.get<EnrichmentData.RadioPlaylist>(EnrichmentType.ARTIST_RADIO_DISCOVERY))
        }
        section("discography", "Discography") {
            r.discography()?.albums?.let { DiscographyGrouping.group(it) }?.map {
                SectionItem(
                    primary = it.displayTitle,
                    secondary = it.year,
                    imageUrl = it.thumbnailUrl,
                    meta = listOfNotNull(it.type, "${it.editionCount} editions".takeIf { _ -> it.editionCount > 1 })
                        .joinToString(" · ")
                        .ifBlank { null },
                    enrich = albumEnrich(it.displayTitle, name),
                )
            }
        }
        section("band_members", "Band Members") {
            r.get<EnrichmentData.BandMembers>(EnrichmentType.BAND_MEMBERS)?.members?.map {
                SectionItem(
                    primary = it.name,
                    secondary = it.role,
                    meta = it.activePeriod,
                    enrich = artistEnrich(it.name),
                )
            }
        }
        section("links", "Links") {
            r.get<EnrichmentData.ArtistLinks>(EnrichmentType.ARTIST_LINKS)?.links?.map {
                val redundant = it.label == null || it.label.equals(it.type, ignoreCase = true)
                SectionItem(primary = it.label ?: it.type, secondary = it.type.takeIf { !redundant }, link = it.url)
            }
        }
        section("timeline", "Timeline") {
            r.get<EnrichmentData.ArtistTimeline>(EnrichmentType.ARTIST_TIMELINE)?.events?.map {
                SectionItem(primary = it.description, secondary = it.date, meta = it.type)
            }
        }
        section("related_genres", "Related Genres") {
            val discovery = r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
            relatedGenresItems(discovery?.relatedGenres.orEmpty())
        }
        section("stats", "Popularity") {
            stats?.statsItems()
        }
    }

    val photo = r.artistPhoto()
    val primaryImage = photo?.url ?: bio?.thumbnailUrl
    val gallery = buildList {
        val seen = mutableSetOf<String>().apply { primaryImage?.let { add(it) } }
        addIfNew(seen, r.get<EnrichmentData.Artwork>(EnrichmentType.ARTIST_LOGO)?.url, "Logo")
        addIfNew(seen, r.get<EnrichmentData.Artwork>(EnrichmentType.ARTIST_BANNER)?.url, "Banner")
        addAlternatives(seen, photo)
    }

    return DemoResponse(
        kind = "artist",
        name = name,
        summary = SummaryCard(
            title = name,
            imageUrl = primaryImage,
            backgroundImageUrl = r.get<EnrichmentData.Artwork>(EnrichmentType.ARTIST_BACKGROUND)?.url,
            text = bio?.text,
            textSource = bio?.source,
            identityResolved = r.identityResolved,
            identityVerdict = r.identityVerdict,
            genres = r.genreTags().map { it.toChip() },
        ),
        sections = sections,
        gallery = gallery,
        meta = r.toMeta(elapsedMs),
    )
}

fun AlbumProfile.toDemoResponse(elapsedMs: Long, artistRadio: Section? = null): DemoResponse {
    val r = results
    val description = r.albumDescription()

    val details = buildList {
        r.label()?.let { add(SectionItem("Label", it)) }
        r.releaseDate()?.let { add(SectionItem("Release date", it)) }
        r.releaseType()?.let { add(SectionItem("Release type", it)) }
        r.country()?.let { add(SectionItem("Country", it)) }
    }

    val sections = buildList {
        r.identity.suggestions.let { s ->
            didYouMeanSection(s) { c -> c.artist?.let { a -> albumEnrich(c.title, a) } }?.let { add(it) }
        }
        if (details.isNotEmpty()) add(Section("details", "Details", details))
        section("tracklist", "Tracklist") {
            r.get<EnrichmentData.Tracklist>(EnrichmentType.ALBUM_TRACKS)?.tracks?.sortedBy { it.position }?.map {
                SectionItem(
                    primary = "#${it.position} ${it.title}",
                    secondary = it.durationMs?.formatDuration(),
                    previewTitle = it.title,
                    previewArtist = artist,
                    previewAlbum = title,
                    enrich = trackEnrich(it.title, artist, title),
                )
            }
        }
        section("similar_albums", "Similar Albums") {
            r.get<EnrichmentData.SimilarAlbums>(EnrichmentType.SIMILAR_ALBUMS)?.albums?.map {
                SectionItem(
                    primary = it.title,
                    secondary = it.artist,
                    imageUrl = it.thumbnailUrl,
                    meta = "score %.2f".format(it.artistMatchScore),
                    enrich = albumEnrich(it.title, it.artist),
                )
            }
        }
        section("editions", "Editions") {
            r.get<EnrichmentData.ReleaseEditions>(EnrichmentType.RELEASE_EDITIONS)?.editions?.map {
                SectionItem(
                    primary = it.title,
                    secondary = listOfNotNull(it.format, it.country, it.year?.toString()).joinToString(" · "),
                    meta = it.label,
                )
            }
        }
        section("related_genres", "Related Genres") {
            val discovery = r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
            relatedGenresItems(discovery?.relatedGenres.orEmpty())
        }
        artistRadio?.takeIf { r.identityResolved }?.let { add(it) }
    }

    val albumArt = r.albumArt()
    val gallery = buildList {
        val seen = mutableSetOf<String>().apply { albumArt?.url?.let { add(it) } }
        addIfNew(seen, r.get<EnrichmentData.Artwork>(EnrichmentType.ALBUM_ART_BACK)?.url, "Back")
        addIfNew(seen, r.get<EnrichmentData.Artwork>(EnrichmentType.ALBUM_BOOKLET)?.url, "Booklet")
        addIfNew(seen, r.get<EnrichmentData.Artwork>(EnrichmentType.CD_ART)?.url, "CD Art")
        addAlternatives(seen, albumArt)
    }

    return DemoResponse(
        kind = "album",
        name = title,
        artist = artist,
        summary = SummaryCard(
            title = title,
            subtitle = artist,
            subtitleEnrich = artistEnrich(artist),
            imageUrl = albumArt?.url,
            text = description?.text,
            textSource = description?.source,
            identityResolved = r.identityResolved,
            identityVerdict = r.identityVerdict,
            genres = r.genreTags().map { it.toChip() },
        ),
        sections = sections,
        gallery = gallery,
        meta = r.toMeta(elapsedMs),
    )
}

/**
 * @param requestedAlbum the album name the caller typed, if any. [TrackProfile.trackMetadata] can
 *   carry a provider-confirmed album title (from the same release-group MusicBrainz's art
 *   resolution already found), which the Details row prefers when present; [requestedAlbum] is
 *   shown, labelled "as entered", only as a fallback when nothing confirmed one.
 */
fun TrackProfile.toDemoResponse(
    elapsedMs: Long,
    artistRadio: Section? = null,
    requestedAlbum: String? = null,
): DemoResponse {
    val r = results
    val lyrics = r.lyrics()
    val stats = r.trackPopularity()

    val trackMetadata = r.trackMetadata()

    val details = buildList {
        trackMetadata?.durationMs?.let { add(SectionItem("Duration", it.formatDuration())) }
        // Provider-confirmed album title wins over the caller's unconfirmed input; "as entered"
        // stays as the fallback when nothing resolved one (see the class doc above).
        val confirmedAlbum = trackMetadata?.albumTitle
        when {
            confirmedAlbum != null -> add(SectionItem("Album", confirmedAlbum))
            requestedAlbum != null -> add(SectionItem("Album", requestedAlbum, meta = "as entered"))
        }
        trackMetadata?.disambiguation?.let { add(SectionItem("Variant", it)) }
    }

    val sections = buildList {
        r.identity.suggestions.let { s ->
            didYouMeanSection(s) { c -> c.artist?.let { a -> trackEnrich(c.title, a) } }?.let { add(it) }
        }
        if (details.isNotEmpty()) add(Section("details", "Details", details))
        section("credits", "Credits") {
            r.credits()?.credits?.map {
                SectionItem(primary = it.name, secondary = it.role, meta = it.roleCategory)
            }
        }
        section("similar_tracks", "Similar Tracks") {
            r.similarTracks()?.tracks?.map {
                SectionItem(
                    primary = it.title,
                    secondary = it.artist,
                    meta = "score %.2f".format(it.matchScore),
                    previewTitle = it.title,
                    previewArtist = it.artist,
                    enrich = trackEnrich(it.title, it.artist),
                )
            }
        }
        section("related_genres", "Related Genres") {
            val discovery = r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
            relatedGenresItems(discovery?.relatedGenres.orEmpty())
        }
        section("stats", "Popularity") {
            stats?.statsItems()
        }
        artistRadio?.takeIf { r.identityResolved }?.let { add(it) }
    }

    return DemoResponse(
        kind = "track",
        name = title,
        artist = artist,
        summary = SummaryCard(
            title = title,
            subtitle = artist,
            subtitleEnrich = artistEnrich(artist),
            imageUrl = r.albumArt()?.url,
            text = lyrics.readingText(),
            textSource = lyrics?.let { "lyrics" },
            previewTitle = title.takeIf { r.identityResolved },
            previewArtist = artist.takeIf { r.identityResolved },
            identityResolved = r.identityResolved,
            identityVerdict = r.identityVerdict,
            genres = r.genreTags().map { it.toChip() },
        ),
        sections = sections,
        meta = r.toMeta(elapsedMs),
    )
}

/**
 * Whether the summary card may present its title/preview as a match. Every `NOT_ATTEMPTED_*`
 * status counts as confident — resolution had nothing to add — while [CanonicalStatus.AMBIGUOUS],
 * [CanonicalStatus.UNRESOLVED], and [CanonicalStatus.FAILED] do not.
 */
private val EnrichmentResults.identityResolved: Boolean
    get() = identity.status !in setOf(CanonicalStatus.AMBIGUOUS, CanonicalStatus.UNRESOLVED, CanonicalStatus.FAILED)

/** The bare [CanonicalStatus] enum name. */
private val EnrichmentResults.identityVerdict: String
    get() = identity.status.name

private fun EnrichmentResults.toMeta(elapsedMs: Long): Meta {
    val hits = raw.entries.sortedBy { it.key.name }.map { (type, result) ->
        when (result) {
            is EnrichmentResult.Success ->
                // A distinct status, not an "ok" suffix: the frontend derives the status-dot class
                // from `status.split(':')[0]`, so a suffix would keep the green dot on a stale hit.
                ProviderHit(type.name, result.provider, if (result.isStale) "ok_stale" else "ok", result.confidence)
            is EnrichmentResult.NotFound ->
                ProviderHit(type.name, result.provider, "not_found")
            is EnrichmentResult.RateLimited ->
                ProviderHit(type.name, result.provider, "rate_limited")
            is EnrichmentResult.Error ->
                ProviderHit(type.name, result.provider, "error: ${result.message}")
        }
    }
    val identitySummary = identity.let { id ->
        listOfNotNull(id.status.name, id.matchScore?.let { "score $it" }).joinToString(" · ").ifBlank { null }
    }
    return Meta(
        elapsedMs = elapsedMs,
        identityMatch = identitySummary,
        providers = hits,
        identifiers = identity.identifiers.toIdentifierHits(),
    )
}

private fun IdentifierNamespace.label(): String = when (this) {
    IdentifierNamespace.MUSICBRAINZ_ARTIST -> "Artist MBID"
    IdentifierNamespace.MUSICBRAINZ_RELEASE -> "Release MBID"
    IdentifierNamespace.MUSICBRAINZ_RECORDING -> "Recording MBID"
    IdentifierNamespace.DISCOGS_ARTIST -> "Discogs"
    IdentifierNamespace.SPOTIFY_ARTIST -> "Spotify"
    IdentifierNamespace.ITUNES_ARTIST -> "iTunes"
    IdentifierNamespace.DEEZER -> "Deezer"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun EnrichmentIdentifiers?.toIdentifierHits(): List<IdentifierHit> {
    if (this == null) return emptyList()
    val structHits = listOfNotNull(
        musicBrainzId?.let { IdentifierHit("MBID", it) },
        musicBrainzReleaseGroupId?.let { IdentifierHit("Release group MBID", it) },
        isrc?.let { IdentifierHit("ISRC", it) },
        barcode?.let { IdentifierHit("Barcode", it) },
        wikidataId?.let { IdentifierHit("Wikidata", it) },
        wikipediaTitle?.let { IdentifierHit("Wikipedia", it) },
    )
    val seenValues = structHits.map { it.value }.toSet()
    val namespacedHits = IdentifierNamespace.entries.mapNotNull { ns ->
        this.get(ns)?.takeIf { it !in seenValues }?.let { IdentifierHit(ns.label(), it) }
    }
    return structHits + namespacedHits
}

private fun MutableList<Section>.section(key: String, label: String, items: () -> List<SectionItem>?) {
    val list = items().orEmpty()
    if (list.isNotEmpty()) add(Section(key, label, list))
}

/**
 * One [SectionItem] per [EnrichmentData.Popularity.signals] entry, in the order core returned
 * them, or today's flat-field row when [EnrichmentData.Popularity.signals] is empty — a
 * pre-migration cache entry that predates the field.
 */
private fun EnrichmentData.Popularity.statsItems(): List<SectionItem> {
    if (signals.isEmpty()) {
        return listOf(
            SectionItem(
                primary = listenerCount?.let { c -> "$c listeners" } ?: "Listener count unavailable",
                secondary = listenCount?.let { c -> "$c listens" },
                meta = rank?.let { rank -> "rank $rank" },
            ),
        )
    }
    return signals.map { signal ->
        SectionItem(
            primary = signal.primaryText(),
            secondary = signal.source,
            meta = signal.normalized?.let { "index %d/100".format((it * 100).toInt()) },
        )
    }
}

private fun PopularitySignal.primaryText(): String =
    when (kind) {
        PopularitySignalKind.LISTEN_COUNT -> "%,d listens".format(value.toLong())
        PopularitySignalKind.LISTENER_COUNT -> "%,d listeners".format(value.toLong())
        PopularitySignalKind.RANK -> "chart rank %,d".format(value.toLong())
        PopularitySignalKind.RATING ->
            "rated %.1f".format(value) + (sampleSize?.let { " by %,d".format(it) } ?: "")
        else -> "%,.0f ${kind.name.lowercase()}".format(value)
    }

/** Appends [url] to the gallery, deduped by [seen] against the primary image and every prior entry. */
private fun MutableList<GalleryImage>.addIfNew(seen: MutableSet<String>, url: String?, label: String) {
    if (url != null && seen.add(url)) add(GalleryImage(url, label))
}

/**
 * Appends [EnrichmentData.Artwork.alternatives] — other providers' images that lost the merge —
 * one per provider, deduped by [seen] the same way as any other gallery entry.
 */
private fun MutableList<GalleryImage>.addAlternatives(seen: MutableSet<String>, artwork: EnrichmentData.Artwork?) {
    artwork?.alternatives?.forEach { alt ->
        if (alt.url.isNotBlank() && seen.add(alt.url)) add(GalleryImage(alt.url, alt.provider))
    }
}

private fun relatedGenresItems(genreDiscovery: List<GenreAffinity>): List<SectionItem>? =
    genreDiscovery.takeIf { it.isNotEmpty() }?.map {
        SectionItem(primary = it.name, secondary = it.relationship, meta = "%.2f".format(it.affinity))
    }

/**
 * "Did you mean?" candidates from [EnrichmentResults.identity], populated only when identity
 * resolution landed on [com.landofoz.musicmeta.CanonicalStatus.AMBIGUOUS]. Each candidate becomes
 * a clickable [SectionItem] via the same [EnrichTarget] flow as any other cross-nav row; a
 * candidate whose fields can't build a valid target (e.g. no artist for an album/track suggestion)
 * is dropped rather than rendered as a dead click. Upstream candidates that differ only by an
 * identifier the row never renders (e.g. release MBID) collapse to indistinguishable duplicates —
 * deduped on the rendered identity (primary/secondary/meta), keeping the first (highest-ranked).
 */
private fun didYouMeanSection(
    suggestions: List<SearchCandidate>,
    target: (SearchCandidate) -> EnrichTarget?,
): Section? {
    val items = suggestions.mapNotNull { candidate ->
        target(candidate)?.let { et ->
            SectionItem(
                primary = candidate.title,
                secondary = candidate.artist,
                imageUrl = candidate.thumbnailUrl,
                meta = listOfNotNull(candidate.year, candidate.disambiguation).joinToString(" · ").ifBlank { null },
                enrich = et,
            )
        }
    }.distinctBy { Triple(it.primary, it.secondary, it.meta) }
    return items.takeIf { it.isNotEmpty() }?.let { Section("did_you_mean", "Did You Mean?", it) }
}

private fun radioItems(radio: EnrichmentData.RadioPlaylist?): List<SectionItem>? = radio?.tracks?.map {
    SectionItem(
        primary = it.title,
        secondary = it.album,
        previewTitle = it.title,
        previewArtist = it.artist,
        previewAlbum = it.album,
        enrich = trackEnrich(it.title, it.artist, it.album),
    )
}

/**
 * Builds the "Radio (from artist)" section shown on track/album pages — same item shape as the
 * artist page's own "Radio" section, since it's the same [EnrichmentType.ARTIST_RADIO] data, just
 * fetched for the track/album's resolved artist rather than an artist lookup itself.
 */
fun artistRadioSection(radio: EnrichmentData.RadioPlaylist?): Section? {
    val items = radioItems(radio).orEmpty()
    return items.takeIf { it.isNotEmpty() }?.let { Section("artist_radio", "Radio (from artist)", it) }
}

/**
 * Internal-navigation targets for [SectionItem.enrich] / [SummaryCard.subtitleEnrich] — one
 * constructor per [EnrichTarget.kind] so a caller can't set `kind` without the fields it needs.
 * Blank names/artists (defensive; the core models this maps from are non-nullable but not
 * guaranteed non-blank) simply produce no target rather than a lookup that can't resolve.
 */
private fun artistEnrich(name: String): EnrichTarget? =
    name.takeIf { it.isNotBlank() }?.let { EnrichTarget("artist", it) }

private fun albumEnrich(title: String, artist: String): EnrichTarget? =
    if (title.isNotBlank() && artist.isNotBlank()) EnrichTarget("album", title, artist = artist) else null

private fun trackEnrich(
    title: String,
    artist: String,
    album: String? = null,
    identifiers: WireIdentifiers? = null,
): EnrichTarget? =
    if (title.isNotBlank() && artist.isNotBlank()) {
        EnrichTarget("track", title, artist = artist, album = album, identifiers = identifiers)
    } else {
        null
    }

/**
 * [EnrichmentIdentifiers] as the wire shape a track row echoes to `/api/preview` and `/api/enrich`.
 * Returns null when it carries no supported track identifier.
 */
private fun EnrichmentIdentifiers.toWireIdentifiers(): WireIdentifiers? {
    val deezerTrackId = get(IdentifierNamespace.DEEZER)
    if (musicBrainzId == null && deezerTrackId == null) return null
    return WireIdentifiers(
        entityKind = WireEntityKind.TRACK,
        musicBrainzId = musicBrainzId,
        deezerTrackId = deezerTrackId,
    )
}

/** One or more consecutive `[mm:ss.xx]`-style LRC timing tags anchored to the start of a line. */
private val LRC_TIMESTAMP_PREFIX = Regex("""^(?:\[\d{1,2}:\d{2}(?:\.\d{1,3})?\]\s*)+""")

/**
 * Full reading-view text for a track's lyrics: [EnrichmentData.Lyrics.plainLyrics] when present,
 * else [EnrichmentData.Lyrics.syncedLyrics] with its per-line LRC timing tags stripped. Never
 * truncated — the frontend owns collapsing/expanding for display.
 */
private fun EnrichmentData.Lyrics?.readingText(): String? =
    this?.plainLyrics ?: this?.syncedLyrics?.lineSequence()?.joinToString("\n") {
        it.replaceFirst(LRC_TIMESTAMP_PREFIX, "")
    }

private fun Long.formatDuration(): String {
    val totalSeconds = this / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun GenreTag.toChip(): GenreChip =
    GenreChip(name = name, curated = curated, confidence = confidence, sources = sources)
