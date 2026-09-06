package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SimilarTrack

/**
 * Deduplicates and merges similar track results from multiple providers.
 *
 * Additive scoring: tracks recommended by multiple providers rank higher — *except* where Last.fm
 * (genuine track-level similarity) and a provider like Deezer (artist-similarity applied to an
 * artist's top tracks, see [SimilarTrack.matchScore]) agree on the same track. There, summing would
 * let an artist-derived approximation inflate a real score, so Last.fm's figure is taken un-summed.
 *
 * That exemption is about the **raw** figure a group carries, not about where the group ranks. Every
 * group's figure is then rescaled against the merged list's own maximum rather than clamped, so a
 * [SimilarTrack.matchScore] is a position within *this* merge and nothing else — it is not
 * comparable against another list's, nor against the figure a provider reported. With three or more
 * contributors a Last.fm group can therefore rank below a group two others agree on, which is what
 * additive scoring means: the un-summed figure is protected from inflation, not from being
 * outranked. That position is fixed at merge time; a served result can still differ from it, because
 * catalog filtering (`catalogFilterMode`) runs afterward and may drop the top-scored entry or
 * reorder the list without touching any score.
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

    /**
     * Merges a list of similar tracks from multiple providers.
     *
     * - Deduplicates by normalized title and artist
     * - Takes the Last.fm score outright for a group Last.fm contributed to, and sums the rest;
     *   then divides every score by the largest of them, so the top entry of *this returned list*
     *   is 1.0 and the spacing below it survives. A list whose maximum is not positive is left as
     *   it is.
     * - Merges sources lists
     * - Merges identifiers: prefers MBID when available, combines extra maps
     * - Returns results sorted by matchScore descending
     *
     * Both the 1.0 top entry and the descending sort are properties of this return value, before
     * catalog filtering (`catalogFilterMode`) — a caller sees them only when no `CatalogProvider`
     * is configured or the mode is `UNFILTERED`. `AVAILABLE_ONLY` can remove the top entry;
     * `AVAILABLE_FIRST` reorders without touching a score.
     */
    internal fun mergeTracks(tracks: List<SimilarTrack>): List<SimilarTrack> {
        if (tracks.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<SimilarTrack>>()
        for (track in tracks) {
            val key = normalize(track.title, track.artist)
            grouped.getOrPut(key) { mutableListOf() }.add(track)
        }

        val summed = grouped.values
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

    private fun normalize(title: String, artist: String): String =
        "${title.trim().lowercase()}:${artist.trim().lowercase()}"
}
