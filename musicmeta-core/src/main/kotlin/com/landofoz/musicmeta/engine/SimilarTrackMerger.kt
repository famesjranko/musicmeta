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

        val grouped = LinkedHashMap<String, MutableList<SimilarTrack>>()
        for (track in tracks) {
            val key = normalize(track.title, track.artist)
            grouped.getOrPut(key) { mutableListOf() }.add(track)
        }

        return grouped.values
            .map { group ->
                val first = group.first()
                val genuineEntry = group.firstOrNull { GENUINE_SOURCE in it.sources }
                val totalScore = genuineEntry?.matchScore ?: group
                    .map { it.matchScore }
                    .fold(0f) { acc, s -> acc + s }
                    .coerceAtMost(1.0f)
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
            .sortedByDescending { it.matchScore }
    }

    private fun normalize(title: String, artist: String): String =
        "${title.trim().lowercase()}:${artist.trim().lowercase()}"
}
