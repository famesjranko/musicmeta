package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SimilarTrack

/**
 * Deduplicates and merges similar track results from multiple providers.
 *
 * Additive scoring: tracks recommended by multiple providers rank higher — *except* when Last.fm
 * (genuine track-level similarity) and Deezer (artist-similarity applied to an artist's top tracks,
 * see [SimilarTrack.matchScore]) agree on the same track. There, summing would let an
 * artist-derived approximation inflate a real score, so Last.fm's score wins outright instead.
 */
internal object SimilarTrackMerger : ResultMerger {

    /** The one source whose [SimilarTrack.matchScore] is genuine track-level similarity. */
    private const val GENUINE_SOURCE = "lastfm"

    override val type: EnrichmentType = EnrichmentType.SIMILAR_TRACKS

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val contributingResults = results.filter {
            (it.data as? EnrichmentData.SimilarTracks)?.tracks?.isNotEmpty() == true
        }
        val allTracks = contributingResults.flatMap { (it.data as EnrichmentData.SimilarTracks).tracks }
        if (allTracks.isEmpty()) return results.first()

        val merged = mergeTracks(allTracks)
        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.SimilarTracks(tracks = merged),
            provider = "similar_track_merger",
            confidence = results.maxOf { it.confidence },
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
            // See weakestProvenance's KDoc for why the merge takes the least-confident contributor.
            provenance = weakestProvenance(contributingResults.map { it.provenance ?: LookupProvenance.FUZZY_NAME }),
        )
    }

    internal fun mergeTracks(tracks: List<SimilarTrack>): List<SimilarTrack> {
        if (tracks.isEmpty()) return emptyList()

        val summed = groupTracks(tracks)
            .map { group ->
                val first = group.first()
                val genuineEntry = group.firstOrNull { GENUINE_SOURCE in it.sources }
                val totalScore = genuineEntry?.matchScore ?: group
                    .map { it.matchScore }
                    .fold(0f) { acc, s -> acc + s }
                val allSources = group.flatMap { it.sources }.distinct()
                val mergedIdentifiers = ResultMerger.mergeIdentifiers(group.map { it.identifiers })

                SimilarTrack(
                    title = first.title,
                    artist = first.artist,
                    matchScore = totalScore,
                    identifiers = mergedIdentifiers,
                    sources = allSources,
                )
            }

        // A list whose maximum is not positive has no scale to divide by, so it passes through
        // untouched — every contributor scoring zero must stay at zero, not become NaN.
        val scale = summed.maxOfOrNull { it.matchScore }?.takeIf { it > 0f } ?: 1f
        return summed
            .map { it.copy(matchScore = it.matchScore / scale) }
            .sortedByDescending { it.matchScore }
    }

    /** The entries of [tracks] that are one recording, grouped in first-occurrence order. */
    internal fun groupTracks(tracks: List<SimilarTrack>): List<List<SimilarTrack>> {
        val grouped = LinkedHashMap<String, MutableList<SimilarTrack>>()
        for (track in tracks) {
            grouped.getOrPut(normalize(track.title, track.artist)) { mutableListOf() }.add(track)
        }
        return grouped.values.toList()
    }

    private fun normalize(title: String, artist: String): String =
        "${title.trim().lowercase()}:${artist.trim().lowercase()}"
}
