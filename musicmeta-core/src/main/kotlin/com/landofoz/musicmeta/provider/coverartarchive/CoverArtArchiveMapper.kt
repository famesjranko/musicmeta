package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.EnrichmentData

/** Maps Cover Art Archive responses to EnrichmentData subclasses. */
internal object CoverArtArchiveMapper {

    fun toArtwork(
        url: String,
        thumbnailUrl: String?,
        image: CoverArtArchiveImage? = null,
    ): EnrichmentData.Artwork {
        val sizes = image?.thumbnails?.map { (label, sizeUrl) ->
            ArtworkSize(url = sizeUrl, label = label)
        }
        return EnrichmentData.Artwork(
            url = url,
            thumbnailUrl = thumbnailUrl,
            sizes = sizes?.takeIf { it.isNotEmpty() },
        )
    }
}
