package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.BandMember
import com.landofoz.musicmeta.Credit
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers

/** Maps Discogs DTOs to EnrichmentData subclasses. */
object DiscogsMapper {

    fun toArtwork(release: DiscogsRelease): EnrichmentData.Artwork? {
        val url = release.coverImage ?: return null
        return EnrichmentData.Artwork(url = url)
    }

    fun toLabelMetadata(release: DiscogsRelease): EnrichmentData.Metadata? {
        val label = release.label ?: return null
        return EnrichmentData.Metadata(label = label)
    }

    fun toReleaseTypeMetadata(release: DiscogsRelease): EnrichmentData.Metadata? {
        val releaseType = release.releaseType ?: return null
        return EnrichmentData.Metadata(releaseType = releaseType)
    }

    fun toAlbumMetadata(release: DiscogsRelease): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            label = release.label,
            releaseDate = release.year,
            releaseType = release.releaseType,
            country = release.country,
            catalogNumber = release.catno,
            genres = (release.genres.orEmpty() + release.styles.orEmpty())
                .takeIf { it.isNotEmpty() },
        )

    fun toCredits(credits: List<DiscogsCredit>): EnrichmentData.Credits =
        EnrichmentData.Credits(
            credits = credits.map { credit ->
                Credit(
                    name = credit.name,
                    role = credit.role,
                    roleCategory = mapRoleCategory(credit.role),
                    identifiers = if (credit.id != null) {
                        EnrichmentIdentifiers().withExtra("discogsArtistId", credit.id.toString())
                    } else EnrichmentIdentifiers(),
                )
            },
        )

    internal fun mapRoleCategory(role: String): String? {
        val lower = role.lowercase()
        return when {
            // Performance
            lower.contains("vocal") -> "performance"
            lower.contains("guitar") -> "performance"
            lower.contains("bass") -> "performance"
            lower.contains("drum") -> "performance"
            lower.contains("keyboard") -> "performance"
            lower.contains("piano") -> "performance"
            lower.contains("percussion") -> "performance"
            lower.contains("instrument") -> "performance"
            lower.contains("perform") -> "performance"
            lower.contains("featuring") -> "performance"
            lower.contains("orchestra") -> "performance"
            lower.contains("choir") -> "performance"
            lower.contains("strings") -> "performance"
            // Production
            lower.contains("produc") -> "production"
            lower.contains("engineer") -> "production"
            lower.contains("mix") -> "production"
            lower.contains("master") -> "production"
            lower.contains("record") -> "production"
            lower.contains("remix") -> "production"
            lower.contains("program") -> "production"
            // Songwriting
            lower.contains("written") -> "songwriting"
            lower.contains("writer") -> "songwriting"
            lower.contains("compos") -> "songwriting"
            lower.contains("lyric") -> "songwriting"
            lower.contains("arrang") -> "songwriting"
            lower.contains("music by") -> "songwriting"
            lower.contains("words by") -> "songwriting"
            else -> null
        }
    }

    fun toBandMembers(artist: DiscogsArtist): EnrichmentData.BandMembers =
        EnrichmentData.BandMembers(
            members = artist.members.map { member ->
                BandMember(
                    name = member.name,
                    identifiers = EnrichmentIdentifiers()
                        .withExtra("discogsArtistId", member.id.toString()),
                )
            },
        )
}
