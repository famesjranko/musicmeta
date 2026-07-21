package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.EnrichmentData

/** Maps Fanart.tv responses to EnrichmentData subclasses. */
internal object FanartTvMapper {

    fun toArtwork(
        image: FanartTvImage,
        allImages: List<FanartTvImage> = emptyList(),
    ): EnrichmentData.Artwork {
        val sizes = allImages.map { img ->
            ArtworkSize(url = img.url, label = img.id)
        }.takeIf { it.size > 1 }
        return EnrichmentData.Artwork(url = image.url, sizes = sizes)
    }
}
