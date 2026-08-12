package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.BandMember
import com.landofoz.musicmeta.Credit
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.ExternalLink
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import com.landofoz.musicmeta.ReleaseEdition
import com.landofoz.musicmeta.TrackInfo

/** Maps MusicBrainz DTOs to EnrichmentData subclasses. */
internal object MusicBrainzMapper {

    fun toAlbumMetadata(release: MusicBrainzRelease): EnrichmentData.Metadata {
        val genreTags = buildGenreTags(release.tagCounts, release.genreCounts, release.curationKnown)
        return EnrichmentData.Metadata(
            genres = genreTags?.map { it.name },
            genreTags = genreTags,
            label = release.label,
            releaseDate = release.date,
            releaseType = release.releaseType,
            country = release.country,
            barcode = release.barcode,
            disambiguation = release.disambiguation,
        )
    }

    fun toAlbumIdentifiers(
        release: MusicBrainzRelease,
        wikidataId: String? = null,
        wikipediaTitle: String? = null,
    ): EnrichmentIdentifiers =
        EnrichmentIdentifiers(
            musicBrainzId = release.id,
            musicBrainzReleaseGroupId = release.releaseGroupId,
            wikidataId = wikidataId,
            wikipediaTitle = wikipediaTitle,
        )

    fun toArtistMetadata(artist: MusicBrainzArtist): EnrichmentData.Metadata {
        val genreTags = buildGenreTags(artist.tagCounts, artist.genreCounts, artist.curationKnown)
        return EnrichmentData.Metadata(
            genres = genreTags?.map { it.name },
            genreTags = genreTags,
            country = artist.country,
            disambiguation = artist.disambiguation,
            artistType = artist.type,
            beginDate = artist.beginDate,
            endDate = artist.endDate,
        )
    }

    fun toArtistIdentifiers(artist: MusicBrainzArtist): EnrichmentIdentifiers =
        EnrichmentIdentifiers(
            musicBrainzId = artist.id,
            wikidataId = artist.wikidataId,
            wikipediaTitle = artist.wikipediaTitle,
        )

    fun toTrackMetadata(recording: MusicBrainzRecording): EnrichmentData.Metadata {
        val genreTags = buildGenreTags(recording.tagCounts, recording.genreCounts, recording.curationKnown)
        return EnrichmentData.Metadata(
            genres = genreTags?.map { it.name },
            genreTags = genreTags,
            isrc = recording.isrcs.firstOrNull(),
        )
    }

    /**
     * Structured track fields dropped by [toTrackMetadata]: duration, the resolved album title
     * (from the same release-group [MusicBrainzRecording.artReleaseGroupId] is drawn from — same
     * tiered fallback, no extra lookup), and disambiguation (already parsed for internal ranking,
     * previously never exposed).
     */
    fun toTrackMetadataDetails(recording: MusicBrainzRecording): EnrichmentData.TrackMetadata =
        EnrichmentData.TrackMetadata(
            durationMs = recording.lengthMs,
            albumTitle = recording.artReleaseGroupTitle,
            disambiguation = recording.disambiguation,
        )

    /**
     * [EnrichmentIdentifiers.musicBrainzId] here is always the **recording** MBID, never a release
     * MBID — [CoverArtArchiveProvider] depends on that contract to tell a track request's identifier
     * apart from an album request's. `musicBrainzReleaseGroupId` is filled from
     * [MusicBrainzRecording.artReleaseGroupId] when one exists, so CAA can try its release-group
     * endpoint for the track's art — that field is tiered rather than strictly the studio album (see
     * its KDoc), because the recording search payload often does not embed the plain Album release
     * at all.
     */
    fun toTrackIdentifiers(recording: MusicBrainzRecording): EnrichmentIdentifiers =
        EnrichmentIdentifiers(
            musicBrainzId = recording.id,
            musicBrainzReleaseGroupId = recording.artReleaseGroupId,
        )

    fun toBandMembers(members: List<MusicBrainzBandMember>): EnrichmentData.BandMembers =
        EnrichmentData.BandMembers(
            members = members.map { member ->
                val period = buildActivePeriod(member)
                BandMember(
                    name = member.name,
                    role = member.role,
                    activePeriod = period,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = member.id),
                )
            },
        )

    /** For solo (Person-type) artists, return the artist themselves as the sole member. */
    fun toSoloArtistMember(artist: MusicBrainzArtist): EnrichmentData.BandMembers {
        // sort-name for Person artists is typically "Last, First" — convert to "First Last"
        val realName = artist.sortName?.let { sortName ->
            val parts = sortName.split(",", limit = 2).map { it.trim() }
            if (parts.size == 2) "${parts[1]} ${parts[0]}" else sortName
        }
        val displayName = if (realName != null && !realName.equals(artist.name, ignoreCase = true)) {
            "$realName (${artist.name})"
        } else {
            artist.name
        }
        val period = listOfNotNull(
            artist.beginDate?.take(4),
            if (artist.endDate != null) artist.endDate.take(4) else "present",
        ).joinToString("-").takeIf { it.isNotEmpty() }
        return EnrichmentData.BandMembers(
            members = listOf(
                BandMember(
                    name = displayName,
                    role = "solo artist",
                    activePeriod = period,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = artist.id),
                ),
            ),
        )
    }

    fun toDiscography(groups: List<MusicBrainzReleaseGroup>): EnrichmentData.Discography =
        EnrichmentData.Discography(
            albums = groups.map { group ->
                DiscographyAlbum(
                    title = group.title,
                    year = group.firstReleaseDate?.take(4),
                    type = group.primaryType,
                    identifiers = EnrichmentIdentifiers(
                        musicBrainzReleaseGroupId = group.id,
                    ),
                )
            },
        )

    fun toTracklist(tracks: List<MusicBrainzTrack>): EnrichmentData.Tracklist =
        EnrichmentData.Tracklist(
            tracks = tracks.map { track ->
                TrackInfo(
                    title = track.title,
                    position = track.position,
                    durationMs = track.durationMs,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = track.id),
                )
            },
        )

    fun toArtistLinks(relations: List<MusicBrainzUrlRelation>): EnrichmentData.ArtistLinks =
        EnrichmentData.ArtistLinks(
            links = relations.map { rel ->
                ExternalLink(type = rel.type, url = rel.url)
            },
        )

    fun toReleaseEditions(detail: MusicBrainzReleaseGroupDetail): EnrichmentData.ReleaseEditions =
        EnrichmentData.ReleaseEditions(
            editions = detail.releases.map { edition ->
                ReleaseEdition(
                    title = edition.title,
                    format = edition.format,
                    country = edition.country,
                    year = edition.date?.take(4)?.toIntOrNull(),
                    label = edition.label,
                    catalogNumber = edition.catalogNumber,
                    barcode = edition.barcode,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = edition.id),
                )
            },
        )

    fun toCredits(credits: List<MusicBrainzCredit>): EnrichmentData.Credits =
        EnrichmentData.Credits(
            credits = credits.map { credit ->
                Credit(
                    name = credit.name,
                    role = credit.role,
                    roleCategory = credit.roleCategory,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = credit.id),
                )
            },
        )

    private fun buildActivePeriod(member: MusicBrainzBandMember): String? {
        val begin = member.beginDate?.take(4) ?: return null
        val end = if (member.ended) (member.endDate?.take(4) ?: "?") else "present"
        return "$begin-$end"
    }

    /**
     * MusicBrainz's curated genres ahead of its community tags, both as [GenreTag]s.
     *
     * The two are different claims about the same entity. `genres` is MusicBrainz's controlled
     * vocabulary — the tags an editor accepted as genres — and `tags` is everything anyone typed,
     * which on a real response includes "british", "parlophone" and "soothing" alongside real
     * genres. So the curated names lead, carry the higher confidence, and are marked
     * [GenreTag.curated] so a consumer (and [com.landofoz.musicmeta.engine.GenreMerger]) can keep
     * them ahead of a community tag that several providers happened to agree on.
     *
     * `genres` is a subset of `tags` carrying the same vote counts, so a curated name is dropped from
     * the community half rather than emitted twice.
     *
     * A tag is marked `curated = false` only where [curationKnown] says the response was one that
     * would have carried the curated names. Where it was not — a search hit, or a lookup that
     * degraded to one — the mark is `null`, meaning nobody asked: a `false` there is an answer the
     * engine's cache would keep for the type's whole TTL, and it would be an answer we invented.
     */
    private fun buildGenreTags(
        tagCounts: List<TagCount>,
        genreCounts: List<TagCount> = emptyList(),
        curationKnown: Boolean = false,
    ): List<GenreTag>? {
        val curatedNames = genreCounts.map { it.name.lowercase() }.toSet()
        val uncurated = if (curationKnown) false else null
        val curated = genreCounts.map { genre ->
            GenreTag(genre.name, CURATED_CONFIDENCE, listOf(PROVIDER_SOURCE), curated = true)
        }
        val community = tagCounts
            .filterNot { it.name.lowercase() in curatedNames }
            .map { tag -> GenreTag(tag.name, TAG_CONFIDENCE, listOf(PROVIDER_SOURCE), curated = uncurated) }
        return (curated + community).takeIf { it.isNotEmpty() }
    }

    /**
     * MusicBrainz's community rating as a popularity claim with a source.
     *
     * A rating is not a count, so it fills no flat field on [EnrichmentData.Popularity] — only the
     * signal list, where [PopularitySignal.sampleSize] carries the vote count that says how much the
     * score rests on. MusicBrainz rates 1–5, so the 0..1 normalisation is exact rather than a guess.
     * Null rating means nobody has voted, which is not a popularity of zero.
     */
    fun toPopularity(rating: MusicBrainzRating?): EnrichmentData.Popularity =
        EnrichmentData.Popularity(
            signals = listOfNotNull(
                rating?.let {
                    PopularitySignal(
                        source = PROVIDER_SOURCE,
                        kind = PopularitySignalKind.RATING,
                        value = it.value.toDouble(),
                        normalized = it.value / MAX_RATING,
                        sampleSize = it.votes,
                    )
                },
            ),
        )

    /** MusicBrainz's rating scale tops out at five stars, so a normalisation needs no calibration. */
    private const val MAX_RATING = 5f

    private const val PROVIDER_SOURCE = "musicbrainz"

    /** A name an editor accepted into MusicBrainz's genre vocabulary, ranked above a free-text tag. */
    private const val CURATED_CONFIDENCE = 0.7f

    /** Free-text community input: a real genre, a mood, a country, or a record label. */
    private const val TAG_CONFIDENCE = 0.4f
}
