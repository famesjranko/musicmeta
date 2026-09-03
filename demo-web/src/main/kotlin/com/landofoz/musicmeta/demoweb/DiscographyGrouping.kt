package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.DiscographyAlbum

/**
 * Collapses a raw discography (one row per provider release — Deezer's `ARTIST_DISCOGRAPHY` catalogs
 * every physical/digital edition separately) into one row per base album. Display only: the title a
 * caller shows and re-enriches is the group's bare base title.
 */
internal object DiscographyGrouping {

    /** One base album's rows collapsed into a single display entry. */
    internal data class GroupedAlbum(
        val displayTitle: String,
        val year: String?,
        val type: String?,
        val thumbnailUrl: String?,
        val editionCount: Int,
    )

    /** A group's identity. Two fields, not a joined string, so no separator can occur in either. */
    private data class GroupKey(val base: String, val type: String)

    /**
     * Groups [albums] by base title and [DiscographyAlbum.type], both case-insensitive — a base
     * title alone would merge Deezer's "Load" album into its "Load" single, dropping one row.
     * Group order and [GroupedAlbum.editionCount] follow the input; within a group, title is the
     * base title, year is the earliest, and type/thumbnail come from the least-qualified entry.
     */
    fun group(albums: List<DiscographyAlbum>): List<GroupedAlbum> {
        val order = mutableListOf<GroupKey>()
        val byKey = mutableMapOf<GroupKey, MutableList<DiscographyAlbum>>()
        for (album in albums) {
            val base = EditionQualifier.baseTitle(album.title)
            val key = GroupKey(base.lowercase(), album.type?.lowercase().orEmpty())
            if (key !in byKey) {
                byKey[key] = mutableListOf()
                order.add(key)
            }
            byKey.getValue(key).add(album)
        }
        return order.map { key ->
            val group = byKey.getValue(key)
            val base = EditionQualifier.baseTitle(group.first().title)
            val canonical = group.firstOrNull { it.title.trim().equals(base, ignoreCase = true) }
                ?: group.minByOrNull { it.title.trim().length }
                ?: group.first()
            val earliestYear = group.mapNotNull { it.year }.minOrNull()
            GroupedAlbum(
                displayTitle = base,
                year = earliestYear,
                type = canonical.type,
                thumbnailUrl = canonical.thumbnailUrl,
                editionCount = group.size,
            )
        }
    }
}

/**
 * Strips trailing edition/reissue qualifier brackets from a provider-sourced title. Only a whole
 * `(...)`/`[...]` group that fully classifies against [KIND_PATTERNS] is stripped, so
 * `"Welcome Home (Sanitarium)"` is untouched; stripping repeats until a group doesn't conform.
 *
 * [KIND_PATTERNS] duplicates the vocabulary in core's `MusicBrainzQualifierFallback` (`internal` to
 * that module, so it cannot be called from here); `scripts/checks/check_edition_vocabulary.py`
 * fails when the two lists diverge. How the patterns are *applied* is deliberately looser than
 * core's and is not compared: a sub-phrase may be a whitespace-separated *sequence* of kinds (see
 * [isQualifierPhrase]), so `"Remastered Deluxe Box Set"` classifies. Safe only because a wrong
 * result here misgroups a display row, where core's guards a live search.
 */
internal object EditionQualifier {

    private data class KindPattern(val kind: String, val pattern: Regex)

    // Order matters: specific kinds first, so "deluxe box set" doesn't classify as bare "deluxe".
    private val KIND_PATTERNS: List<KindPattern> = listOf(
        KindPattern(
            "remaster",
            Regex("""(\d{4}\s+)?remaster(ed)?(\s+version)?(\s+\d{4})?""", RegexOption.IGNORE_CASE),
        ),
        KindPattern("super_deluxe", Regex("""super\s+deluxe(\s+edition)?""", RegexOption.IGNORE_CASE)),
        KindPattern("deluxe_box_set", Regex("""deluxe\s+box\s*set""", RegexOption.IGNORE_CASE)),
        KindPattern("box_set", Regex("""box\s*set""", RegexOption.IGNORE_CASE)),
        KindPattern("anniversary_edition", Regex("""anniversary(\s+edition)?""", RegexOption.IGNORE_CASE)),
        KindPattern("expanded_edition", Regex("""expanded(\s+edition)?""", RegexOption.IGNORE_CASE)),
        KindPattern("special_edition", Regex("""special\s+edition""", RegexOption.IGNORE_CASE)),
        KindPattern("collectors_edition", Regex("""collector'?s\s+edition""", RegexOption.IGNORE_CASE)),
        KindPattern("legacy_edition", Regex("""legacy\s+edition""", RegexOption.IGNORE_CASE)),
        KindPattern("limited_edition", Regex("""limited\s+edition""", RegexOption.IGNORE_CASE)),
        KindPattern("bonus_track_version", Regex("""bonus\s+track(s)?(\s+version)?""", RegexOption.IGNORE_CASE)),
        KindPattern("deluxe", Regex("""deluxe(\s+edition)?""", RegexOption.IGNORE_CASE)),
        KindPattern("reissue", Regex("""reissue""", RegexOption.IGNORE_CASE)),
        KindPattern("edition", Regex("""edition""", RegexOption.IGNORE_CASE)),
    )

    /** A trailing `(...)`/`[...]` group, delimiter types not mixed (`"(Deluxe]"` must not match). */
    private val TRAILING_GROUP = Regex("""\s*(?:\(([^()]*)\)|\[([^\[\]]*)])\s*$""")

    private fun classifyKind(phrase: String): String? =
        KIND_PATTERNS.firstOrNull { it.pattern.matches(phrase) }?.kind

    /**
     * Whether [phrase] is fully consumed by one or more whitespace-separated kind matches. Tries
     * every kind at the start of [phrase] and recurses on the remainder, so a leading match that
     * leads nowhere backtracks. Terminates because every match consumes at least one character.
     */
    private fun isQualifierPhrase(phrase: String): Boolean {
        val trimmed = phrase.trim()
        if (trimmed.isEmpty()) return false
        if (classifyKind(trimmed) != null) return true
        for (kindPattern in KIND_PATTERNS) {
            val match = kindPattern.pattern.find(trimmed) ?: continue
            if (match.range.first != 0) continue
            val rest = trimmed.substring(match.range.last + 1).trim()
            if (rest.isNotEmpty() && isQualifierPhrase(rest)) return true
        }
        return false
    }

    /** Whether every `/`-, `&`-, or `,`-separated sub-phrase of [content] is a qualifier phrase. */
    private fun isQualifierGroup(content: String): Boolean {
        for (rawPart in content.split(Regex("[/&,]"))) {
            val part = rawPart.trim()
            if (part.isEmpty() || !isQualifierPhrase(part)) return false
        }
        return true
    }

    /** [title] with every trailing qualifying `(...)`/`[...]` group stripped; never empty. */
    fun baseTitle(title: String): String {
        var cur = title.trim()
        while (true) {
            val match = TRAILING_GROUP.find(cur) ?: break
            val content = match.groups[1]?.value ?: match.groups[2]?.value ?: break
            if (!isQualifierGroup(content)) break
            cur = cur.substring(0, match.range.first).trimEnd()
        }
        return cur.ifEmpty { title.trim() }
    }
}
