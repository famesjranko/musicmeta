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
import com.landofoz.musicmeta.engine.DEFAULT_SYNTHESIZER_DEPENDENCIES

/**
 * @param pending the enrichment types that have not settled yet, `requestedTypes - raw.keys` on a
 *   streaming snapshot and empty on a completed result. Empty is what makes a completed response
 *   the same shape it has always been: no card and no summary slot can claim to be loading.
 */
fun ArtistProfile.toDemoResponse(elapsedMs: Long, pending: Set<EnrichmentType> = emptySet()): DemoResponse {
    val r = results
    val bio = r.biography()
    val stats = r.artistPopularity()
    val linker = CreditLinker("artist", name, artist = null, identifiers = r.identity.identifiers)

    val details = buildList {
        r.country()?.let { add(SectionItem("Country", it)) }
    }

    val sections = buildSections(r, linker, pending) {
        r.identity.suggestions.let { s ->
            didYouMeanSection(s) { artistEnrich(it.title) }?.let { add(it) }
        }
        if (details.isNotEmpty()) {
            add(Section("details", "Details", details, creditsFor(EnrichmentType.COUNTRY)))
        }
        section("similar_artists", "Similar Artists", EnrichmentType.SIMILAR_ARTISTS) {
            r.similarArtists()?.artists?.map {
                SectionItem(
                    primary = it.name,
                    secondary = "match ${(it.matchScore * 100).toInt()}%",
                    meta = it.sources.joinToString(", ").ifBlank { null },
                    enrich = artistEnrich(it.name),
                )
            }
        }
        section("top_tracks", "Top Tracks", EnrichmentType.ARTIST_TOP_TRACKS) {
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
        section("radio", "Radio", EnrichmentType.ARTIST_RADIO) { radioItems(r.radio()) }
        section("radio_discovery", "Radio Discovery", EnrichmentType.ARTIST_RADIO_DISCOVERY) {
            radioItems(r.get<EnrichmentData.RadioPlaylist>(EnrichmentType.ARTIST_RADIO_DISCOVERY))
        }
        section("discography", "Discography", EnrichmentType.ARTIST_DISCOGRAPHY) {
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
        section("band_members", "Band Members", EnrichmentType.BAND_MEMBERS) {
            r.get<EnrichmentData.BandMembers>(EnrichmentType.BAND_MEMBERS)?.members?.map {
                SectionItem(
                    primary = it.name,
                    secondary = it.role,
                    meta = it.activePeriod,
                    enrich = artistEnrich(it.name),
                )
            }
        }
        section("links", "Links", EnrichmentType.ARTIST_LINKS) {
            r.get<EnrichmentData.ArtistLinks>(EnrichmentType.ARTIST_LINKS)?.links?.map {
                val redundant = it.label == null || it.label.equals(it.type, ignoreCase = true)
                SectionItem(primary = it.label ?: it.type, secondary = it.type.takeIf { !redundant }, link = it.url)
            }
        }
        section("timeline", "Timeline", EnrichmentType.ARTIST_TIMELINE) {
            r.get<EnrichmentData.ArtistTimeline>(EnrichmentType.ARTIST_TIMELINE)?.events?.map {
                SectionItem(primary = it.description, secondary = it.date, meta = it.type)
            }
        }
        section("related_genres", "Related Genres", EnrichmentType.GENRE_DISCOVERY) {
            val discovery = r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
            relatedGenresItems(discovery?.relatedGenres.orEmpty())
        }
        section("stats", "Popularity", EnrichmentType.ARTIST_POPULARITY) {
            stats?.statsItems()
        }
    }

    val photo = r.artistPhoto()
    val primaryImage = photo?.url ?: bio?.thumbnailUrl
    val gallery = buildList {
        val seen = mutableSetOf<String>().apply { primaryImage?.let { add(it) } }
        addArtwork(seen, r, EnrichmentType.ARTIST_LOGO, "Logo", linker)
        addArtwork(seen, r, EnrichmentType.ARTIST_BANNER, "Banner", linker)
        addAlternatives(seen, photo, linker)
    }

    val genreChips = r.genreTags().map { it.toChip() }
    val imageCredit = when {
        photo != null -> r.artworkCredit(EnrichmentType.ARTIST_PHOTO, photo, primaryImage, linker)
        bio?.thumbnailUrl != null -> r.credit(EnrichmentType.ARTIST_BIO, linker)
        else -> null
    }
    return DemoResponse(
        kind = "artist",
        name = name,
        summary = SummaryCard(
            title = name,
            imageUrl = primaryImage?.fanartTvPreviewUrl(),
            imageCredit = imageCredit,
            backgroundImageUrl = r.get<EnrichmentData.Artwork>(EnrichmentType.ARTIST_BACKGROUND)
                ?.url
                ?.fanartTvPreviewUrl(),
            text = bio?.text,
            textSource = bio?.source,
            textCredit = bio?.let { r.credit(EnrichmentType.ARTIST_BIO, linker) },
            genreCredits = linker.genreCredits(genreChips, r),
            identityResolved = r.identityResolved,
            identityVerdict = r.identityVerdict,
            genres = genreChips,
            pendingSlots = summaryPendingSlots(
                pending,
                imageTypes = setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BIO),
                textType = EnrichmentType.ARTIST_BIO,
                hasImage = primaryImage != null,
                hasText = bio?.text != null,
                hasGenres = genreChips.isNotEmpty(),
            ),
        ),
        sections = sections,
        gallery = gallery,
        meta = r.toMeta(elapsedMs),
    )
}

/** @param pending see [ArtistProfile.toDemoResponse]. */
fun AlbumProfile.toDemoResponse(
    elapsedMs: Long,
    artistRadio: Section? = null,
    pending: Set<EnrichmentType> = emptySet(),
): DemoResponse {
    val r = results
    val description = r.albumDescription()
    val linker = CreditLinker("album", title, artist, r.identity.identifiers)

    val details = buildList {
        r.label()?.let { add(SectionItem("Label", it)) }
        r.releaseDate()?.let { add(SectionItem("Release date", it)) }
        r.releaseType()?.let { add(SectionItem("Release type", it)) }
        r.country()?.let { add(SectionItem("Country", it)) }
    }

    val sections = buildSections(r, linker, pending) {
        r.identity.suggestions.let { s ->
            // An album lookup's candidates legitimately include singles (MusicBrainz calls both
            // releases); surface the entity kind the user searched for first, stably.
            val albumsFirst = s.sortedByDescending { it.releaseType == "Album" }
            didYouMeanSection(albumsFirst) { c -> c.artist?.let { a -> albumEnrich(c.title, a) } }?.let { add(it) }
        }
        if (details.isNotEmpty()) {
            add(
                Section(
                    "details",
                    "Details",
                    details,
                    creditsFor(
                        EnrichmentType.LABEL,
                        EnrichmentType.RELEASE_DATE,
                        EnrichmentType.RELEASE_TYPE,
                        EnrichmentType.COUNTRY,
                        EnrichmentType.ALBUM_METADATA,
                    ),
                ),
            )
        }
        section("tracklist", "Tracklist", EnrichmentType.ALBUM_TRACKS) {
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
        section("similar_albums", "Similar Albums", EnrichmentType.SIMILAR_ALBUMS) {
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
        section("editions", "Editions", EnrichmentType.RELEASE_EDITIONS) {
            r.get<EnrichmentData.ReleaseEditions>(EnrichmentType.RELEASE_EDITIONS)?.editions?.map {
                SectionItem(
                    primary = it.title,
                    secondary = listOfNotNull(it.format, it.country, it.year?.toString()).joinToString(" · "),
                    meta = it.label,
                )
            }
        }
        section("related_genres", "Related Genres", EnrichmentType.GENRE_DISCOVERY) {
            val discovery = r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
            relatedGenresItems(discovery?.relatedGenres.orEmpty())
        }
        artistRadio?.takeIf { r.identityResolved }?.let { add(it) }
    }

    val albumArt = r.albumArt()
    val cardImage = albumArt.cardImageUrl()
    val gallery = buildList {
        val seen = mutableSetOf<String>().apply { albumArt?.url?.let { add(it) } }
        addArtwork(seen, r, EnrichmentType.ALBUM_ART_BACK, "Back", linker)
        addArtwork(seen, r, EnrichmentType.ALBUM_BOOKLET, "Booklet", linker)
        addArtwork(seen, r, EnrichmentType.CD_ART, "CD Art", linker)
        addAlternatives(seen, albumArt, linker)
    }

    val genreChips = r.genreTags().map { it.toChip() }
    return DemoResponse(
        kind = "album",
        name = title,
        artist = artist,
        summary = SummaryCard(
            title = title,
            subtitle = artist,
            subtitleEnrich = artistEnrich(artist),
            imageUrl = cardImage,
            imageCredit = r.artworkCredit(EnrichmentType.ALBUM_ART, albumArt, cardImage, linker),
            text = description?.text,
            textSource = description?.source,
            textCredit = description?.let { r.credit(EnrichmentType.ALBUM_DESCRIPTION, linker) },
            genreCredits = linker.genreCredits(genreChips, r),
            identityResolved = r.identityResolved,
            identityVerdict = r.identityVerdict,
            genres = genreChips,
            pendingSlots = summaryPendingSlots(
                pending,
                imageTypes = setOf(EnrichmentType.ALBUM_ART),
                textType = EnrichmentType.ALBUM_DESCRIPTION,
                hasImage = albumArt?.url != null,
                hasText = description?.text != null,
                hasGenres = genreChips.isNotEmpty(),
            ),
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
 * @param pending see [ArtistProfile.toDemoResponse].
 */
fun TrackProfile.toDemoResponse(
    elapsedMs: Long,
    artistRadio: Section? = null,
    requestedAlbum: String? = null,
    pending: Set<EnrichmentType> = emptySet(),
): DemoResponse {
    val r = results
    val lyrics = r.lyrics()
    val stats = r.trackPopularity()
    val linker = CreditLinker("track", title, artist, r.identity.identifiers)

    val trackMetadata = r.trackMetadata()

    val details = buildList {
        trackMetadata?.durationMs?.let { add(SectionItem("Duration", it.formatDuration())) }
        val confirmedAlbum = trackMetadata?.albumTitle
        when {
            confirmedAlbum != null -> add(SectionItem("Album", confirmedAlbum))
            requestedAlbum != null -> add(SectionItem("Album", requestedAlbum, meta = "as entered"))
        }
        trackMetadata?.disambiguation?.let { add(SectionItem("Variant", it)) }
    }

    val sections = buildSections(r, linker, pending) {
        r.identity.suggestions.let { s ->
            didYouMeanSection(s) { c -> c.artist?.let { a -> trackEnrich(c.title, a) } }?.let { add(it) }
        }
        if (details.isNotEmpty()) {
            add(Section("details", "Details", details, creditsFor(EnrichmentType.TRACK_METADATA)))
        }
        section("credits", "Credits", EnrichmentType.CREDITS) {
            r.credits()?.credits?.map {
                SectionItem(primary = it.name, secondary = it.role, meta = it.roleCategory)
            }
        }
        section("similar_tracks", "Similar Tracks", EnrichmentType.SIMILAR_TRACKS) {
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
        section("related_genres", "Related Genres", EnrichmentType.GENRE_DISCOVERY) {
            val discovery = r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
            relatedGenresItems(discovery?.relatedGenres.orEmpty())
        }
        section("stats", "Popularity", EnrichmentType.TRACK_POPULARITY) {
            stats?.statsItems()
        }
        artistRadio?.takeIf { r.identityResolved }?.let { add(it) }
    }

    val genreChips = r.genreTags().map { it.toChip() }
    return DemoResponse(
        kind = "track",
        name = title,
        artist = artist,
        summary = SummaryCard(
            title = title,
            subtitle = artist,
            subtitleEnrich = artistEnrich(artist),
            imageUrl = r.albumArt().cardImageUrl(),
            imageCredit = r.artworkCredit(EnrichmentType.ALBUM_ART, r.albumArt(), r.albumArt().cardImageUrl(), linker),
            text = lyrics.readingText(),
            textSource = lyrics?.let { "lyrics" },
            textCredit = lyrics?.let { r.lyricsCredit(linker) },
            genreCredits = linker.genreCredits(genreChips, r),
            previewTitle = title.takeIf { r.identityResolved },
            previewArtist = artist.takeIf { r.identityResolved },
            identityResolved = r.identityResolved,
            identityVerdict = r.identityVerdict,
            genres = genreChips,
            pendingSlots = summaryPendingSlots(
                pending,
                imageTypes = setOf(EnrichmentType.ALBUM_ART),
                textType = EnrichmentType.LYRICS_PLAIN,
                hasImage = r.albumArt()?.url != null,
                hasText = lyrics.readingText() != null,
                hasGenres = genreChips.isNotEmpty(),
            ),
        ),
        sections = sections,
        meta = r.toMeta(elapsedMs),
    )
}

/**
 * Whether the summary card may present its title/preview as a match. Every `NOT_ATTEMPTED_*`
 * status counts as confident — resolution had nothing to add — while [CanonicalStatus.RESOLVING]
 * (resolution has not settled yet), [CanonicalStatus.AMBIGUOUS], [CanonicalStatus.UNRESOLVED], and
 * [CanonicalStatus.FAILED] do not.
 */
private val EnrichmentResults.identityResolved: Boolean
    get() = identity.status !in setOf(
        CanonicalStatus.RESOLVING,
        CanonicalStatus.AMBIGUOUS,
        CanonicalStatus.UNRESOLVED,
        CanonicalStatus.FAILED,
    )

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
                ProviderHit(type.name, result.provider, "error: ${result.message}", errorKind = result.errorKind.name)
        }
    }
    val identitySummary = identity.let { id ->
        listOfNotNull(id.status.name, id.matchScore?.let { "score %.2f".format(it) })
            .joinToString(" · ")
            .ifBlank { null }
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

/**
 * Collects the cards of one profile against the set of enrichment types that have not settled yet.
 * [pending] is empty for a completed result, which is what makes the non-streaming response
 * identical to what it was before streaming existed: with nothing pending, no placeholder can be
 * produced.
 */
private fun buildSections(
    results: EnrichmentResults,
    linker: CreditLinker,
    pending: Set<EnrichmentType>,
    build: SectionBuilder.() -> Unit,
): List<Section> = SectionBuilder(results, linker, pending).apply(build).sections

/** @see buildSections */
private class SectionBuilder(
    private val results: EnrichmentResults,
    private val linker: CreditLinker,
    private val pending: Set<EnrichmentType>,
) {
    val sections = mutableListOf<Section>()

    /** Who a card drawing on [types] must credit — see [EnrichmentResults.creditProviders]. */
    fun creditsFor(vararg types: EnrichmentType): List<SourceCredit> =
        results.creditProviders(*types).map { linker.credit(it) }

    /** Adds an already-built card — one assembled from several types, or from identity rather than a type. */
    fun add(section: Section) {
        sections += section
    }

    /**
     * One card, or a placeholder holding its position. [types] are the enrichment types this card
     * renders: while any of them is still to settle and nothing has filled the card yet, it goes
     * out empty and `pending`, so the card cannot appear later and reflow the page under a reader.
     * With nothing pending an empty card is omitted, exactly as before.
     */
    fun section(key: String, label: String, vararg types: EnrichmentType, items: () -> List<SectionItem>?) {
        val list = items().orEmpty()
        when {
            list.isNotEmpty() -> sections += Section(key, label, list, creditsFor(*types))
            types.any { it in pending } -> sections += Section(key, label, emptyList(), pending = true)
        }
    }
}

/** The engine reserves this suffix for a result it merged; such a result names no upstream. */
private const val MERGER_SUFFIX = "_merger"

/**
 * The upstreams behind [types], in first-seen order and one entry each. A merged result attributes
 * per item rather than per result, so its items' own `sources` are read instead of the merger's id.
 */
private fun EnrichmentResults.creditProviders(vararg types: EnrichmentType): List<String> =
    types.flatMap { creditProvidersOf(it) }.distinct()

private fun EnrichmentResults.creditProvidersOf(type: EnrichmentType): List<String> {
    // A synthesized type names its synthesizer, which no reader can be sent to and no upstream's
    // terms cover; credit whoever answered the types it derives from. The graph is core's, read
    // off DEFAULT_SYNTHESIZER_DEPENDENCIES so a synthesizer added or re-wired there stays correct.
    DEFAULT_SYNTHESIZER_DEPENDENCIES[type]?.let { sources -> return sources.flatMap { creditProvidersOf(it) } }
    val success = raw[type] as? EnrichmentResult.Success ?: return emptyList()
    return if (success.provider.endsWith(MERGER_SUFFIX)) success.data.itemSources() else listOf(success.provider)
}

/** Every upstream the items of a merged payload name. Empty for a payload that carries none. */
private fun EnrichmentData.itemSources(): List<String> = when (this) {
    is EnrichmentData.SimilarArtists -> artists.flatMap { it.sources }
    is EnrichmentData.SimilarTracks -> tracks.flatMap { it.sources }
    is EnrichmentData.TopTracks -> tracks.flatMap { it.sources }
    is EnrichmentData.Popularity -> signals.map { it.source }
    is EnrichmentData.Metadata -> genreTags.orEmpty().flatMap { it.sources }
    else -> emptyList()
}

/**
 * Builds one entity's link-backs. What a namespaced identifier names depends on what was asked
 * for — the same MusicBrainz id is an artist, a release or a recording — so a linker belongs to
 * one response and is never shared across kinds.
 */
private class CreditLinker(
    private val kind: String,
    private val name: String,
    private val artist: String?,
    private val identifiers: EnrichmentIdentifiers?,
) {
    fun credit(provider: String): SourceCredit = SourceCredit(provider, linkFor(provider))

    fun credits(providers: List<String>): List<SourceCredit> = providers.distinct().map { credit(it) }

    /**
     * Who the genre chips must credit. The tags carry their own sources, which is the merged
     * result's per-item attribution; the type's own provider answers only for a payload whose tags
     * predate that field.
     */
    fun genreCredits(chips: List<GenreChip>, results: EnrichmentResults): List<SourceCredit> {
        if (chips.isEmpty()) return emptyList()
        val sources = chips.flatMap { it.sources }
            .ifEmpty { results.creditProviders(EnrichmentType.GENRE, EnrichmentType.ALBUM_METADATA) }
        return credits(sources)
    }

    private fun linkFor(provider: String): String? = when (provider) {
        "musicbrainz" -> identifiers?.musicBrainzId?.let { "https://musicbrainz.org/${musicBrainzEntity()}/$it" }
        // The Cover Art Archive's own URLs are the API's; MusicBrainz's cover-art tab is the page a
        // reader can look at, and only an album request's id names the release it hangs off.
        "coverartarchive" -> identifiers?.musicBrainzId
            ?.takeIf { kind == "album" }
            ?.let { "https://musicbrainz.org/release/$it/cover-art" }
        "wikipedia" -> identifiers?.wikipediaTitle
            ?.let { "https://en.wikipedia.org/wiki/${encode(it.replace(' ', '_'))}" }
        "wikidata" -> identifiers?.wikidataId?.let { "https://www.wikidata.org/wiki/$it" }
        "discogs" -> discogsLink()
        "itunes" -> identifiers?.get(IdentifierNamespace.ITUNES_ARTIST)?.let { "https://music.apple.com/artist/$it" }
        "deezer", "deezer-similar-albums" ->
            identifiers?.get(IdentifierNamespace.DEEZER)?.let { "https://www.deezer.com/$kind/$it" }
        "lastfm" -> lastFmLink()
        else -> null
    }

    private fun musicBrainzEntity(): String = when (kind) {
        "album" -> "release"
        "track" -> "recording"
        else -> "artist"
    }

    /**
     * The Discogs page for this entity: its own page when resolution settled on a Discogs id, and
     * otherwise a Discogs search for the name — the response carries no release-level Discogs id to
     * address, and their terms want the link next to the data either way.
     */
    private fun discogsLink(): String {
        val artistId = identifiers?.get(IdentifierNamespace.DISCOGS_ARTIST)
        if (kind == "artist" && artistId != null) return "https://www.discogs.com/artist/$artistId"
        val query = listOfNotNull(artist, name).joinToString(" ")
        val type = if (kind == "artist") "artist" else "release"
        return "https://www.discogs.com/search/?q=${encode(query)}&type=$type"
    }

    /** Last.fm's catalogue page for this entity, which their terms require a link back to. */
    private fun lastFmLink(): String? = when (kind) {
        "artist" -> "https://www.last.fm/music/${encode(name)}"
        "album" -> artist?.let { "https://www.last.fm/music/${encode(it)}/${encode(name)}" }
        "track" -> artist?.let { "https://www.last.fm/music/${encode(it)}/_/${encode(name)}" }
        else -> null
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}

/**
 * The summary card's slots whose data has not settled — see [SummaryCard.pendingSlots]. A slot a
 * settled type already filled is never listed, so a snapshot never shows a loading state over
 * something the reader can already see.
 */
private fun summaryPendingSlots(
    pending: Set<EnrichmentType>,
    imageTypes: Set<EnrichmentType>,
    textType: EnrichmentType,
    hasImage: Boolean,
    hasText: Boolean,
    hasGenres: Boolean,
): List<String> = buildList {
    if (!hasImage && imageTypes.any { it in pending }) add("image")
    if (!hasText && textType in pending) add("text")
    if (!hasGenres && EnrichmentType.GENRE in pending) add("genres")
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

/**
 * Appends the artwork [type] resolved to, credited to whoever answered it and deduped by [seen]
 * against the primary image and every prior entry.
 */
private fun MutableList<GalleryImage>.addArtwork(
    seen: MutableSet<String>,
    results: EnrichmentResults,
    type: EnrichmentType,
    label: String,
    linker: CreditLinker,
) {
    val url = results.get<EnrichmentData.Artwork>(type)?.url ?: return
    if (seen.add(url)) add(GalleryImage(url, label, results.credit(type, linker)))
}

/** The credit for whichever provider answered [type], or null when nothing did. */
private fun EnrichmentResults.credit(type: EnrichmentType, linker: CreditLinker): SourceCredit? =
    (raw[type] as? EnrichmentResult.Success)?.provider?.let { linker.credit(it) }

/** Lyrics come from either lyrics type, so the credit follows whichever one answered. */
private fun EnrichmentResults.lyricsCredit(linker: CreditLinker): SourceCredit? =
    credit(EnrichmentType.LYRICS_SYNCED, linker) ?: credit(EnrichmentType.LYRICS_PLAIN, linker)

/**
 * The credit for the artwork actually painted. [url] is the URL the card renders, which is not
 * always the ranked primary — a faster alternative wins the card (see [cardImageUrl]) and is a
 * different provider's image, so crediting the result's own provider would credit the wrong one.
 */
private fun EnrichmentResults.artworkCredit(
    type: EnrichmentType,
    artwork: EnrichmentData.Artwork?,
    url: String?,
    linker: CreditLinker,
): SourceCredit? {
    if (url == null) return null
    val alternative = artwork?.alternatives?.firstOrNull { it.url == url }
    return alternative?.let { linker.credit(it.provider) } ?: credit(type, linker)
}

/**
 * Appends [EnrichmentData.Artwork.alternatives] — other providers' images that lost the merge —
 * one per provider, deduped by [seen] the same way as any other gallery entry.
 */
private fun MutableList<GalleryImage>.addAlternatives(
    seen: MutableSet<String>,
    artwork: EnrichmentData.Artwork?,
    linker: CreditLinker,
) {
    artwork?.alternatives?.forEach { alt ->
        if (alt.url.isNotBlank() && seen.add(alt.url)) {
            add(GalleryImage(alt.url, alt.provider, linker.credit(alt.provider)))
        }
    }
}

/** Suffix-matched hosts of CDNs fast enough to paint a card image without a visible delay. */
private val FAST_ART_CDN_HOSTS = listOf("dzcdn.net", "mzstatic.com")

private fun String.hasFastCdnHost(): Boolean {
    val host = runCatching { java.net.URI(this).host }.getOrNull() ?: return false
    return FAST_ART_CDN_HOSTS.any { host == it || host.endsWith(".$it") }
}

/**
 * The URL to paint the summary card with: a [EnrichmentData.Artwork.alternatives] entry hosted on
 * a fast CDN (Deezer, iTunes/Apple) when one exists, since Cover Art Archive's own redirect-then-serve
 * path is tens of times slower. Falls back to the ranked primary [EnrichmentData.Artwork.url]
 * otherwise, including when this artwork is `null`.
 */
private fun EnrichmentData.Artwork?.cardImageUrl(): String? {
    if (this == null) return null
    if (url.hasFastCdnHost()) return url
    return alternatives?.firstOrNull { it.url.hasFastCdnHost() }?.url ?: url
}

private const val FANART_TV_HOST = "assets.fanart.tv"

/**
 * A fanart.tv URL rewritten from its full-size `/fanart/` path to the smaller `/preview/` path
 * fanart.tv serves at the same location, for summary-card use where the full-size original is
 * unnecessary. Only rewrites [FANART_TV_HOST] URLs; anything else is returned unchanged.
 */
private fun String.fanartTvPreviewUrl(): String {
    val host = runCatching { java.net.URI(this).host }.getOrNull()
    if (host != FANART_TV_HOST) return this
    return replaceFirst("/fanart/", "/preview/")
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
                meta = listOfNotNull(candidate.releaseType, candidate.year, candidate.disambiguation)
                    .joinToString(" · ").ifBlank { null },
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
fun artistRadioSection(artist: String, results: EnrichmentResults): Section? {
    val items = radioItems(results.radio()).orEmpty()
    if (items.isEmpty()) return null
    val linker = CreditLinker("artist", artist, artist = null, identifiers = results.identity.identifiers)
    val credits = linker.credits(results.creditProviders(EnrichmentType.ARTIST_RADIO))
    return Section("artist_radio", "Radio (from artist)", items, credits)
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
