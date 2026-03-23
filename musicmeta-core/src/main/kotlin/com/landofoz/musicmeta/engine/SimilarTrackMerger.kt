package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SimilarTrack

/**
 * Deduplicates and merges similar track results from multiple providers.
 * Additive scoring: tracks recommended by multiple providers rank higher.
 */
object SimilarTrackMerger : ResultMerger {

    override val type: EnrichmentType = EnrichmentType.SIMILAR_TRACKS

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val allTracks = results.flatMap { result ->
            (result.data as? EnrichmentData.SimilarTracks)?.tracks.orEmpty()
        }
        if (allTracks.isEmpty()) return results.first()

        val merged = mergeTracks(allTracks)
        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.SimilarTracks(tracks = merged),
            provider = "similar_track_merger",
            confidence = results.maxOf { it.confidence },
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
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
                val totalScore = group
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
