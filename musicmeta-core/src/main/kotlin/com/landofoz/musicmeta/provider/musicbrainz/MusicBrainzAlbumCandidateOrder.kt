package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.SearchTitleMatcher

/** Orders exact-title pressings by artist-id set, then release group and first credited artist, before the limit. */
internal object MusicBrainzAlbumCandidateOrder {
    fun <T> order(
        candidates: List<T>, title: String, releaseOf: (T) -> MusicBrainzRelease?,
    ): List<T> {
        val keys = candidates.map { candidate ->
            releaseOf(candidate)?.takeIf { SearchTitleMatcher.equivalent(title, it.title) }?.let { artistKey(it) }
        }
        val groups = candidates.indices.filter { keys[it] != null }.groupBy { keys[it] }
        val result = candidates.toMutableList()
        rankSlots(result, groups.values, releaseOf)
        val editions = result.indices.mapNotNull { index ->
            releaseOf(result[index])?.takeIf {
                SearchTitleMatcher.equivalent(title, it.title) && !it.releaseGroupId.isNullOrBlank() &&
                    it.artistCreditIds.isNotEmpty()
            }?.let { index to it }
        }.toMap()
        val editionGroups = editions.keys.groupBy { index ->
            val release = editions.getValue(index)
            release.releaseGroupId to release.artistCreditIds.first()
        }
        rankSlots(result, editionGroups.values, releaseOf)
        return result
    }

    private fun <T> rankSlots(
        candidates: MutableList<T>, groups: Collection<List<Int>>, releaseOf: (T) -> MusicBrainzRelease?,
    ) {
        groups.forEach { slots ->
            val ordered = slots.map { candidates[it] }
                .sortedWith(compareBy(releaseOrder) { checkNotNull(releaseOf(it)) })
            slots.sorted().zip(ordered).forEach { (index, candidate) -> candidates[index] = candidate }
        }
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
