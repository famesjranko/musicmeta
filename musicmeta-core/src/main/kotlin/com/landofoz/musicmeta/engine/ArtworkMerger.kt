package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Merges artwork results from multiple providers into a single result.
 * The highest-confidence provider becomes the primary image (url/thumbnailUrl/sizes).
 * Remaining providers are included as [ArtworkSource] alternatives.
 *
 * Parameterized by type so one class handles ARTIST_PHOTO, ALBUM_ART, etc.
 */
class ArtworkMerger(override val type: EnrichmentType) : ResultMerger {

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val artworkResults = results.filter { it.data is EnrichmentData.Artwork }
        if (artworkResults.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        // Primary = highest confidence; ties broken by provider order (first in chain)
        val sorted = artworkResults.sortedByDescending { it.confidence }
        val primary = sorted.first()
        val primaryArtwork = primary.data as EnrichmentData.Artwork

        // Remaining providers become alternatives (excluding duplicates of primary URL)
        val alternatives = sorted.drop(1)
            .map { result ->
                val art = result.data as EnrichmentData.Artwork
                ArtworkSource(
                    provider = result.provider,
                    url = art.url,
                    thumbnailUrl = art.thumbnailUrl,
                    sizes = art.sizes,
                )
            }
            .filter { it.url != primaryArtwork.url }
            .distinctBy { it.url }

        val merged = primaryArtwork.copy(
            alternatives = alternatives.takeIf { it.isNotEmpty() },
        )

        return EnrichmentResult.Success(
            type = type,
            data = merged,
            provider = primary.provider,
            confidence = primary.confidence,
            resolvedIdentifiers = ResultMerger.mergeIdentifiers(
                artworkResults.mapNotNull { it.resolvedIdentifiers },
            ),
        )
    }
}
