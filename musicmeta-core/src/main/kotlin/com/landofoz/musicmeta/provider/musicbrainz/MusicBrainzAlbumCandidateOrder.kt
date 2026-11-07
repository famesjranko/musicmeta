package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.SearchTitleMatcher

/** Orders exact-title pressings inside their existing artist slots, before the result limit. */
internal object MusicBrainzAlbumCandidateOrder {
    fun <T> order(
        candidates: List<T>, title: String, releaseOf: (T) -> MusicBrainzRelease?,
    ): List<T> {
        val keys = candidates.map { candidate ->
            releaseOf(candidate)?.takeIf { SearchTitleMatcher.equivalent(title, it.title) }?.let { artistKey(it) }
        }
        val groups = candidates.indices.filter { keys[it] != null }.groupBy { keys[it] }
        val result = candidates.toMutableList()
        groups.values.forEach { slots ->
            val ordered = slots.map { candidates[it] }
                .sortedWith(compareBy(releaseOrder) { checkNotNull(releaseOf(it)) })
            slots.zip(ordered).forEach { (index, candidate) -> result[index] = candidate }
        }
        return result
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

    // Search carries pressing dates, not the group's first-release date; a partial date keeps its precision.
    private fun dateKey(date: String?): String? = date?.takeIf { DATE.matches(it) }

    private val DATE = Regex("\\d{4}(-\\d{2}(-\\d{2})?)?")
}
