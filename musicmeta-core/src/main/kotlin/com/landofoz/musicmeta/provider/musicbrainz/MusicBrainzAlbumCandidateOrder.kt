package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.SearchTitleMatcher

/**
 * Orders each exact-title component once, joining artist sets or first credited artists across groups
 * within a shared supplied-artist and hint tier.
 */
internal object MusicBrainzAlbumCandidateOrder {
    fun <T> order(
        candidates: List<T>,
        title: String,
        artistTierOf: (T) -> MusicBrainzCandidateArtistTier = { MusicBrainzCandidateArtistTier() },
        releaseOf: (T) -> MusicBrainzRelease?,
    ): List<T> {
        val releases = candidates.indices.mapNotNull { index ->
            releaseOf(candidates[index])?.takeIf { SearchTitleMatcher.equivalent(title, it.title) }
                ?.let { index to it }
        }.toMap()
        val artists = releases.keys.filter { artistKey(releases.getValue(it)) != null }.groupBy {
            artistTierOf(candidates[it]) to artistKey(releases.getValue(it))
        }
        val editions = releases.keys.filter {
            releases.getValue(it).artistCreditIds.isNotEmpty()
        }.groupBy {
            artistTierOf(candidates[it]) to releases.getValue(it).artistCreditIds.first()
        }
        val parents = IntArray(candidates.size) { it }
        (artists.values + editions.values).forEach { related ->
            related.drop(1).forEach { index -> parents[root(parents, index)] = root(parents, related.first()) }
        }
        val components = releases.keys.groupBy { root(parents, it) }
        val result = candidates.toMutableList()
        components.values.forEach { slots ->
            val ordered = slots.map { candidates[it] }
                .sortedWith(compareBy(releaseOrder) { checkNotNull(releaseOf(it)) })
            slots.zip(ordered).forEach { (index, candidate) -> result[index] = candidate }
        }
        return result
    }

    private fun root(parents: IntArray, index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }

    private fun artistKey(release: MusicBrainzRelease): List<String>? =
        if (release.artistCreditIds.isNotEmpty()) {
            listOf("ids") + release.artistCreditIds.map { it.lowercase() }.sorted()
        } else {
            release.artistCredit?.takeIf { it.isNotBlank() }?.let { listOf("credit", it.trim().lowercase()) }
        }

    private val releaseOrder = compareByDescending<MusicBrainzRelease> { complete(it) }
        .thenByDescending { it.status == "Official" }
        .thenByDescending { it.releaseType == "Album" }
        .thenByDescending { it.secondaryTypes.isEmpty() }
        .thenBy { dateKey(it.date) ?: "\uffff" }
        .thenByDescending { it.disambiguation.isNullOrBlank() }
        .thenByDescending { it.score }
        .thenBy { it.id }

    private fun complete(release: MusicBrainzRelease): Boolean =
        dateKey(release.date) != null && !release.status.isNullOrBlank() && !release.releaseType.isNullOrBlank()

    // Unknown components trail known dates in the same period, without moving across a year or month.
    private fun dateKey(date: String?): String? =
        date?.takeIf { DATE.matches(it) }?.let { (it + "-99-99").take(10) }

    private val DATE = Regex("\\d{4}(-\\d{2}(-\\d{2})?)?")
}
