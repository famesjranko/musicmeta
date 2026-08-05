package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.TrackProfile

fun ArtistProfile.toDemoResponse(elapsedMs: Long): DemoResponse {
    val r = results
    val genres = r.genres()
    val bio = r.biography()
    val stats = r.artistPopularity()

    val sections = buildList {
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
                SectionItem(
                    primary = it.title,
                    secondary = it.album,
                    meta = "#${it.rank}",
                    previewTitle = it.title,
                    previewArtist = name,
                    previewAlbum = it.album,
                    enrich = trackEnrich(it.title, name, it.album),
                )
            }
        }
        section("radio", "Radio") { radioItems(r.radio()) }
        section("discography", "Discography") {
            r.discography()?.albums?.map {
                SectionItem(
                    primary = it.title,
                    secondary = it.year,
                    imageUrl = it.thumbnailUrl,
                    meta = it.type,
                    enrich = albumEnrich(it.title, name),
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
            r.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)?.relatedGenres?.map {
                SectionItem(primary = it.name, secondary = it.relationship, meta = "%.2f".format(it.affinity))
            }
        }
        section("stats", "Popularity") {
            stats?.let {
                listOf(
                    SectionItem(
                        primary = it.listenerCount?.let { c -> "$c listeners" } ?: "Listener count unavailable",
                        secondary = it.listenCount?.let { c -> "$c listens" },
                        meta = it.rank?.let { rank -> "rank $rank" },
                    ),
                )
            }
        }
    }

    return DemoResponse(
        kind = "artist",
        name = name,
        summary = SummaryCard(
            title = name,
            subtitle = genres.joinToString(", ").ifBlank { null },
            imageUrl = r.artistPhoto()?.url,
            text = bio?.text,
            textSource = bio?.source,
        ),
        sections = sections,
        meta = r.toMeta(elapsedMs),
    )
}

fun AlbumProfile.toDemoResponse(elapsedMs: Long, artistRadio: Section? = null): DemoResponse {
    val r = results

    val details = buildList {
        r.label()?.let { add(SectionItem("Label", it)) }
        r.releaseDate()?.let { add(SectionItem("Release date", it)) }
        r.releaseType()?.let { add(SectionItem("Release type", it)) }
        r.country()?.let { add(SectionItem("Country", it)) }
        r.genres().takeIf { it.isNotEmpty() }?.let { add(SectionItem("Genres", it.joinToString(", "))) }
    }

    val sections = buildList {
        if (details.isNotEmpty()) add(Section("details", "Details", details))
        section("tracklist", "Tracklist") {
            r.get<EnrichmentData.Tracklist>(EnrichmentType.ALBUM_TRACKS)?.tracks?.sortedBy { it.position }?.map {
                SectionItem(
                    primary = "#${it.position} ${it.title}",
                    secondary = it.durationMs?.formatDuration(),
                    previewTitle = it.title,
                    previewArtist = artist,
                    previewAlbum = title,
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
        artistRadio?.let { add(it) }
    }

    return DemoResponse(
        kind = "album",
        name = title,
        artist = artist,
        summary = SummaryCard(
            title = title,
            subtitle = artist,
            subtitleEnrich = artistEnrich(artist),
            imageUrl = r.albumArt()?.url,
        ),
        sections = sections,
        meta = r.toMeta(elapsedMs),
    )
}

fun TrackProfile.toDemoResponse(elapsedMs: Long, artistRadio: Section? = null): DemoResponse {
    val r = results
    val lyrics = r.lyrics()
    val stats = r.trackPopularity()

    val details = buildList {
        r.genres().takeIf { it.isNotEmpty() }?.let { add(SectionItem("Genres", it.joinToString(", "))) }
    }

    val sections = buildList {
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
        section("stats", "Popularity") {
            stats?.let {
                listOf(
                    SectionItem(
                        primary = it.listenerCount?.let { c -> "$c listeners" } ?: "Listener count unavailable",
                        secondary = it.listenCount?.let { c -> "$c listens" },
                        meta = it.rank?.let { rank -> "rank $rank" },
                    ),
                )
            }
        }
        artistRadio?.let { add(it) }
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
            text = (lyrics?.syncedLyrics ?: lyrics?.plainLyrics)?.take(400),
            textSource = lyrics?.let { "lyrics" },
            previewTitle = title,
            previewArtist = artist,
        ),
        sections = sections,
        meta = r.toMeta(elapsedMs),
    )
}

private fun EnrichmentResults.toMeta(elapsedMs: Long): Meta {
    val hits = raw.entries.sortedBy { it.key.name }.map { (type, result) ->
        when (result) {
            is EnrichmentResult.Success ->
                ProviderHit(type.name, result.provider, "ok", result.confidence)
            is EnrichmentResult.NotFound ->
                ProviderHit(type.name, result.provider, "not_found")
            is EnrichmentResult.RateLimited ->
                ProviderHit(type.name, result.provider, "rate_limited")
            is EnrichmentResult.Error ->
                ProviderHit(type.name, result.provider, "error: ${result.message}")
        }
    }
    val identitySummary = identity?.let { id ->
        listOfNotNull(id.match?.name, id.matchScore?.let { "score $it" }).joinToString(" · ").ifBlank { null }
    }
    return Meta(elapsedMs = elapsedMs, identityMatch = identitySummary, providers = hits)
}

private fun MutableList<Section>.section(key: String, label: String, items: () -> List<SectionItem>?) {
    val list = items().orEmpty()
    if (list.isNotEmpty()) add(Section(key, label, list))
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

private fun trackEnrich(title: String, artist: String, album: String? = null): EnrichTarget? =
    if (title.isNotBlank() &&
        artist.isNotBlank()) EnrichTarget("track", title, artist = artist, album = album) else null

private fun Long.formatDuration(): String {
    val totalSeconds = this / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
