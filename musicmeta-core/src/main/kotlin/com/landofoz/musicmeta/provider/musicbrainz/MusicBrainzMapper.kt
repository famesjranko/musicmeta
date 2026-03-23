package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.BandMember
import com.landofoz.musicmeta.Credit
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.ExternalLink
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.ReleaseEdition
import com.landofoz.musicmeta.TrackInfo

/** Maps MusicBrainz DTOs to EnrichmentData subclasses. */
object MusicBrainzMapper {

    fun toAlbumMetadata(release: MusicBrainzRelease): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            genres = release.tags.takeIf { it.isNotEmpty() },
            genreTags = buildGenreTags(release.tagCounts),
            label = release.label,
            releaseDate = release.date,
            releaseType = release.releaseType,
            country = release.country,
            barcode = release.barcode,
            disambiguation = release.disambiguation,
        )

    fun toAlbumIdentifiers(release: MusicBrainzRelease): EnrichmentIdentifiers =
        EnrichmentIdentifiers(
            musicBrainzId = release.id,
            musicBrainzReleaseGroupId = release.releaseGroupId,
        )

    fun toArtistMetadata(artist: MusicBrainzArtist): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            genres = artist.tags.takeIf { it.isNotEmpty() },
            genreTags = buildGenreTags(artist.tagCounts),
            country = artist.country,
            disambiguation = artist.disambiguation,
            artistType = artist.type,
            beginDate = artist.beginDate,
            endDate = artist.endDate,
        )

    fun toArtistIdentifiers(artist: MusicBrainzArtist): EnrichmentIdentifiers =
        EnrichmentIdentifiers(
            musicBrainzId = artist.id,
            wikidataId = artist.wikidataId,
            wikipediaTitle = artist.wikipediaTitle,
        )

    fun toTrackMetadata(recording: MusicBrainzRecording): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            genres = recording.tags.takeIf { it.isNotEmpty() },
            genreTags = buildGenreTags(recording.tagCounts),
            isrc = recording.isrcs.firstOrNull(),
        )

    fun toTrackIdentifiers(recording: MusicBrainzRecording): EnrichmentIdentifiers =
        EnrichmentIdentifiers(musicBrainzId = recording.id)

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

    private fun buildGenreTags(tagCounts: List<TagCount>): List<GenreTag>? =
        tagCounts.map { tag ->
            GenreTag(name = tag.name, confidence = 0.4f, sources = listOf("musicbrainz"))
        }.takeIf { it.isNotEmpty() }
}
