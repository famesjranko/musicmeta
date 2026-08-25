package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.EnrichmentData

/** Maps Cover Art Archive responses to EnrichmentData subclasses. */
internal object CoverArtArchiveMapper {

    /**
     * Every URL leaves this mapper with the https scheme. CAA's stored index JSON fixes the
     * scheme at upload time, so older entries still serve `http://` URLs, which a browser blocks
     * as mixed content on an https page. The upgrade is limited to the two hosts CAA serves from,
     * both of which serve https; any other host keeps its scheme.
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

    // Scheme and host compare case-insensitively (RFC 3986), and the authority may carry
    // userinfo or a port; everything after the scheme is preserved as received.
    private fun upgradeArchiveScheme(url: String): String {
        val schemeLength = "http://".length
        if (!url.regionMatches(0, "http://", 0, schemeLength, ignoreCase = true)) return url
        val rest = url.substring(schemeLength)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        val isArchiveHost = host == "coverartarchive.org" || host == "archive.org" ||
            host.endsWith(".coverartarchive.org") || host.endsWith(".archive.org")
        return if (isArchiveHost) "https://$rest" else url
    }
}
