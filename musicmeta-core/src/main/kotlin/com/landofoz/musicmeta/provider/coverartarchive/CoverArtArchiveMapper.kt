package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.EnrichmentData

/** Maps Cover Art Archive responses to EnrichmentData subclasses. */
internal object CoverArtArchiveMapper {

    /**
     * Every URL leaves this mapper with the https scheme. CAA's stored index JSON fixes the
     * scheme at upload time, so older entries still serve `http://` URLs, which a browser blocks
     * as mixed content on an https page. The upgrade is limited to the two hosts CAA serves from,
     * where https is verified to return the same bytes; any other host keeps its scheme.
     */
    fun toArtwork(
        url: String,
        thumbnailUrl: String?,
        image: CoverArtArchiveImage? = null,
    ): EnrichmentData.Artwork {
        val sizes = image?.thumbnails?.map { (label, sizeUrl) ->
            ArtworkSize(url = upgradeArchiveScheme(sizeUrl), label = label)
        }
        return EnrichmentData.Artwork(
            url = upgradeArchiveScheme(url),
            thumbnailUrl = thumbnailUrl?.let(::upgradeArchiveScheme),
            sizes = sizes?.takeIf { it.isNotEmpty() },
        )
    }

    private fun upgradeArchiveScheme(url: String): String {
        if (!url.startsWith("http://")) return url
        val host = url.substring("http://".length).substringBefore('/').substringBefore(':')
        val isArchiveHost = host == "coverartarchive.org" || host == "archive.org" ||
            host.endsWith(".coverartarchive.org") || host.endsWith(".archive.org")
        return if (isArchiveHost) "https://" + url.substring("http://".length) else url
    }
}
