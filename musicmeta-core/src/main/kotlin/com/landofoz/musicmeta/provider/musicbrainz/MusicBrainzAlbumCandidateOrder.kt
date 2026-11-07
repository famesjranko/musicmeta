package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.SearchTitleMatcher

/** Orders exact-title pressings within artist credits and compatible release-group editions before the limit. */
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
        rankSlots(result, editionGroups(editions), releaseOf)
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

    private fun editionGroups(releases: Map<Int, MusicBrainzRelease>): List<List<Int>> =
        releases.keys.groupBy { releases.getValue(it).releaseGroupId }.values.flatMap { slots ->
            val groups = mutableListOf<MutableList<Int>>()
            slots.sortedBy { releases.getValue(it).id }.forEach { index ->
                val release = releases.getValue(index)
                // Every pair must overlap: a guest credit cannot bridge otherwise disjoint artists.
                val group = groups.firstOrNull { members ->
                    members.all { other ->
                        release.artistCreditIds.any { it in releases.getValue(other).artistCreditIds }
                    }
                }
                if (group == null) groups.add(mutableListOf(index)) else group.add(index)
            }
            groups
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
