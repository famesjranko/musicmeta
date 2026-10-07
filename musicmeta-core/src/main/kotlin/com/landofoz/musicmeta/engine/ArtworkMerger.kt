package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance

/**
 * Merges artwork results from multiple providers into a single result.
 * The highest-confidence provider becomes the primary image (url/thumbnailUrl/sizes).
 * Remaining providers are included as [ArtworkSource] alternatives.
 *
 * Parameterized by type so one class handles ARTIST_PHOTO, ALBUM_ART, etc.
 */
internal class ArtworkMerger(override val type: EnrichmentType) : ResultMerger {

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val artworkResults = results.filter { it.data is EnrichmentData.Artwork }
        if (artworkResults.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val distinctImages = distinctArtworkImages(artworkResults)
        if (distinctImages.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        return mergedArtworkResult(distinctImages, artworkResults)
    }

    private fun distinctArtworkImages(
        artworkResults: List<EnrichmentResult.Success>,
    ): List<EnrichmentResult.Success> = expandedArtworkResults(artworkResults)
        .groupBy { (it.data as EnrichmentData.Artwork).url }
        .values
        .filter(::hasConsistentAttribution)
        .map(::representativeImage)

    private fun expandedArtworkResults(
        artworkResults: List<EnrichmentResult.Success>,
    ): List<EnrichmentResult.Success> = artworkResults
        // Primary = highest confidence; ties broken by provider order (first in chain).
        .sortedByDescending { it.confidence }
        .flatMap(::withArtworkAlternatives)

    private fun withArtworkAlternatives(result: EnrichmentResult.Success): List<EnrichmentResult.Success> {
        val artwork = result.data as EnrichmentData.Artwork
        return listOf(result) + artwork.alternatives.orEmpty().map { alternative ->
            result.copy(
                provider = alternative.provider,
                data = EnrichmentData.Artwork(
                    url = alternative.url,
                    thumbnailUrl = alternative.thumbnailUrl,
                    sizes = alternative.sizes,
                    attribution = alternative.attribution,
                ),
            )
        }
    }

    // A URL group with contradictory file facts is unsafe to represent as one image.
    private fun hasConsistentAttribution(candidates: List<EnrichmentResult.Success>): Boolean = candidates
        .mapNotNull { (it.data as EnrichmentData.Artwork).attribution }
        .distinct()
        .size <= 1

    private fun representativeImage(candidates: List<EnrichmentResult.Success>): EnrichmentResult.Success =
        candidates.firstOrNull { (it.data as EnrichmentData.Artwork).attribution != null } ?: candidates.first()

    private fun mergedArtworkResult(
        distinctImages: List<EnrichmentResult.Success>,
        artworkResults: List<EnrichmentResult.Success>,
    ): EnrichmentResult.Success {
        val primary = distinctImages.first()
        val primaryArtwork = primary.data as EnrichmentData.Artwork

        // Each alternate keeps the complete tuple from the provider that supplied that image.
        val alternatives = distinctImages.drop(1)
            .map { result ->
                val art = result.data as EnrichmentData.Artwork
                ArtworkSource(
                    provider = result.provider,
                    url = art.url,
                    thumbnailUrl = art.thumbnailUrl,
                    sizes = art.sizes,
                    attribution = art.attribution,
                )
            }

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
            // See weakestProvenance's KDoc for why the merge takes the least-confident contributor.
            provenance = weakestProvenance(artworkResults.map { it.provenance ?: LookupProvenance.FUZZY_NAME }),
        )
    }
}
