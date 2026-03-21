package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.GenreTag

/** Pure function that normalizes, deduplicates, and merges genre tags from multiple providers. */
object GenreMerger {

    /** Genre alias map for normalization — maps common variants to canonical forms. */
    private val ALIASES = mapOf(
        "alt rock" to "alternative rock",
        "hip hop" to "hip-hop",
        "hiphop" to "hip-hop",
        "rnb" to "r&b",
        "r & b" to "r&b",
        "electronica" to "electronic",
        "synth pop" to "synthpop",
        "post punk" to "post-punk",
        "indie rock" to "indie rock",
    )

    /**
     * Merges a list of genre tags from multiple providers.
     *
     * - Normalizes tag names (lowercase, trim, alias mapping)
     * - Deduplicates by normalized name: sums confidences (capped at 1.0), merges sources
     * - Preserves first-seen display name (casing from highest-priority provider)
     * - Returns results sorted by confidence descending
     */
    fun merge(tags: List<GenreTag>): List<GenreTag> {
        if (tags.isEmpty()) return emptyList()

        // Group tags by normalized name, preserving insertion order for first-seen display name
        val grouped = LinkedHashMap<String, MutableList<GenreTag>>()
        for (tag in tags) {
            val key = normalize(tag.name)
            grouped.getOrPut(key) { mutableListOf() }.add(tag)
        }

        return grouped.entries
            .map { (normalizedKey, group) ->
                val firstSeen = group.first()
                // Determine display name: use the first-seen name but apply alias resolution
                val displayName = if (firstSeen.name.trim().lowercase() in ALIASES) {
                    normalizedKey
                } else {
                    firstSeen.name
                }
                val totalConfidence = group
                    .map { it.confidence }
                    .fold(0f) { acc, c -> acc + c }
                    .coerceAtMost(1.0f)
                val allSources = group.flatMap { it.sources }
                GenreTag(
                    name = displayName,
                    confidence = totalConfidence,
                    sources = allSources,
                )
            }
            .sortedByDescending { it.confidence }
    }

    /** Normalizes a genre name: lowercase, trim, apply alias mapping. */
    internal fun normalize(name: String): String {
        val trimmed = name.trim().lowercase()
        return ALIASES[trimmed] ?: trimmed
    }
}
