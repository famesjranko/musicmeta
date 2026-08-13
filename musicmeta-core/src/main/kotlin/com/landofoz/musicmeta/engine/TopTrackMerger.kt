package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.TopTrack

/**
 * Merges top tracks from all providers (Last.fm, ListenBrainz, Deezer). Each provider fetches its
 * API max. Deduplicates by normalized title (+ MBID when available), sums listen counts, merges
 * sources, and ranks by combined popularity. Returns everything — the developer filters to their
 * needs.
 */
internal object TopTrackMerger : ResultMerger {

    override val type: EnrichmentType = EnrichmentType.ARTIST_TOP_TRACKS

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val contributingResults = results.filter {
            (it.data as? EnrichmentData.TopTracks)?.tracks?.isNotEmpty() == true
        }
        val allTracks = contributingResults.flatMap { (it.data as EnrichmentData.TopTracks).tracks }
        if (allTracks.isEmpty()) return results.first()

        val merged = merge(allTracks)
        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.TopTracks(tracks = merged),
            provider = "top_track_merger",
            confidence = results.maxOf { it.confidence },
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
            // No single provider's route speaks for a merged result; the weakest contributing
            // route is the smallest truthful summary. A contributor built outside the engine's
            // pre-merge stamp (e.g. a direct unit-test fixture) falls back to FUZZY_NAME, the same
            // conservative default observedProvenance uses for a route it cannot otherwise place.
            provenance = weakestProvenance(contributingResults.map { it.provenance ?: LookupProvenance.FUZZY_NAME }),
        )
    }

    fun merge(tracks: List<TopTrack>): List<TopTrack> {
        if (tracks.isEmpty()) return emptyList()

        // Group by normalized title, preferring MBID-based matching when available
        val grouped = LinkedHashMap<String, MutableList<TopTrack>>()
        for (track in tracks) {
            val mbid = track.identifiers.musicBrainzId
            val key = if (mbid != null) "mbid:$mbid" else normalize(track.title)
            // Check if this MBID matches an existing name-based group (or vice versa)
            val existingKey = if (mbid != null) {
                grouped.keys.firstOrNull { k ->
                    !k.startsWith("mbid:") &&
                        grouped[k]?.any {
                            it.identifiers.musicBrainzId == mbid
                        } == true
                }
            } else {
                grouped.keys.firstOrNull { k ->
                    k.startsWith("mbid:") &&
                        grouped[k]?.any {
                            normalize(it.title) == normalize(track.title)
                        } == true
                }
            }
            grouped.getOrPut(existingKey ?: key) { mutableListOf() }.add(track)
        }

        return grouped.values.map { group ->
            val best = group.maxByOrNull { it.listenCount ?: 0L } ?: group.first()
            val totalListens = group.mapNotNull { it.listenCount }.sum().takeIf { it > 0 }
            val maxListeners = group.mapNotNull { it.listenerCount }.maxOrNull()
            val allSources = group.flatMap { it.sources }.distinct()
            val mergedIds = ResultMerger.mergeIdentifiers(group.map { it.identifiers })
            TopTrack(
                title = best.title,
                artist = best.artist,
                album = group.firstNotNullOfOrNull { it.album },
                durationMs = group.firstNotNullOfOrNull { it.durationMs },
                listenCount = totalListens,
                listenerCount = maxListeners,
                rank = 0, // re-ranked below
                sources = allSources,
                identifiers = mergedIds,
            )
        }
            .sortedByDescending { it.listenCount ?: 0L }
            .mapIndexed { index, track -> track.copy(rank = index + 1) }
    }

    private fun normalize(title: String): String = title.trim().lowercase()
}
