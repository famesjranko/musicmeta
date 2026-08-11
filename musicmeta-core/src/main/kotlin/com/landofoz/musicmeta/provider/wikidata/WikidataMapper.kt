package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.ExternalLink
import com.landofoz.musicmeta.IdentifierNamespace

/** Maps Wikidata responses to EnrichmentData subclasses. */
internal object WikidataMapper {

    fun toArtwork(imageUrl: String): EnrichmentData.Artwork =
        EnrichmentData.Artwork(url = imageUrl)

    fun toArtistLinks(officialWebsite: String): EnrichmentData.ArtistLinks =
        EnrichmentData.ArtistLinks(links = listOf(ExternalLink(type = "official homepage", url = officialWebsite)))

    /**
     * The external-id claims as identifiers, or null when the entity carries none. Spotify (P1902)
     * and Apple Music (P2850) are absent on most long-tail acts, which is not an error.
     */
    fun toIdentifiers(props: WikidataEntityProperties): EnrichmentIdentifiers? {
        val pairs = listOf(
            IdentifierNamespace.MUSICBRAINZ_ARTIST to props.musicBrainzArtistId,
            IdentifierNamespace.DISCOGS_ARTIST to props.discogsArtistId,
            IdentifierNamespace.SPOTIFY_ARTIST to props.spotifyArtistId,
            IdentifierNamespace.ITUNES_ARTIST to props.iTunesArtistId,
        ).mapNotNull { (ns, value) -> value?.let { ns to it } }
        if (pairs.isEmpty()) return null
        return pairs.fold(EnrichmentIdentifiers()) { ids, (ns, value) -> ids.with(ns, value) }
    }

    fun toMetadata(props: WikidataEntityProperties): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            beginDate = props.birthDate,
            endDate = props.deathDate,
            country = props.countryOfOrigin,
            artistType = props.occupation,
        )
}
