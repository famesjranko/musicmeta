package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData

/** Maps Wikidata responses to EnrichmentData subclasses. */
internal object WikidataMapper {

    fun toArtwork(imageUrl: String): EnrichmentData.Artwork =
        EnrichmentData.Artwork(url = imageUrl)

    fun toMetadata(props: WikidataEntityProperties): EnrichmentData.Metadata =
        EnrichmentData.Metadata(
            beginDate = props.birthDate,
            endDate = props.deathDate,
            country = props.countryOfOrigin,
            artistType = props.occupation,
        )
}
