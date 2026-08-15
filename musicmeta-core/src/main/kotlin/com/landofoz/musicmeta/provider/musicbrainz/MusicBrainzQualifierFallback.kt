package com.landofoz.musicmeta.provider.musicbrainz

/**
 * Candidate generation and release/recording selection when a provider-sourced title carries a
 * reissue/edition qualifier — bracketed (`"Master Of Puppets (Remastered)"`) or dash-form
 * (`"Starman - 2012 Remaster"`) — that MusicBrainz's own title does not, which makes the
 * exact-quoted-Lucene search come back empty. Order matters: some MusicBrainz titles legitimately
 * contain edition wording (`"2112 (deluxe edition)"`) or a dash of their own, so the caller's exact
 * title is always tried first and stripped candidates only follow when it comes up empty. Both
 * shapes share the same [QualifierTag] vocabulary; only how a candidate is peeled differs.
 *
 * A bare title routinely ties 25+ releases at MusicBrainz's maximum score, and the winner is
 * consumer-visible ([MusicBrainzMapper.toAlbumIdentifiers] propagates its date, label, country,
 * barcode and MBIDs). [tagEvidence] scores how well a release evidences the qualifier stripped to
 * reach it; [MusicBrainzReleaseRanking.pickBestRelease] is what ranks on it.
 */
internal object MusicBrainzQualifierFallback {

    /**
     * A qualifier's structured meaning: which reissue/edition [kind] it names, and the specific
     * [year] it states, if any. Matched by full kind-phrase against a candidate's disambiguation/
     * title text (never a bare word like `"edition"` alone — see [KIND_KEYWORDS]).
     */
    internal data class QualifierTag(val kind: String, val year: String? = null)

    /** One title to search, and the tags actually peeled off the original title to reach it. */
    internal data class FallbackCandidate(val title: String, val removedTags: List<QualifierTag>)

    private data class KindPattern(val kind: String, val pattern: Regex)

    /**
     * Deliberately excludes `live`/`mono`/`stereo`/`edit`/`version`/`explicit` — those name a
     * different edition, not a different pressing, so stripping them risks matching the wrong
     * release. Specific kinds come first, so `"deluxe box set"` doesn't classify as bare `deluxe`.
     */
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

    /**
     * Full-phrase keywords each kind must match against a candidate's disambiguation/title text —
     * NOT bare words. `edition` appears in no keyword list, its own included, so the generic word
     * can never cross-match a release whose disambiguation merely contains it.
     */
    private val KIND_KEYWORDS: Map<String, List<String>> = mapOf(
        "remaster" to listOf("remaster", "remastered"),
        "super_deluxe" to listOf("super deluxe"),
        "deluxe_box_set" to listOf("deluxe box set"),
        "box_set" to listOf("box set"),
        "anniversary_edition" to listOf("anniversary edition", "anniversary"),
        "expanded_edition" to listOf("expanded edition", "expanded"),
        "special_edition" to listOf("special edition"),
        "collectors_edition" to listOf("collector's edition", "collectors edition"),
        "legacy_edition" to listOf("legacy edition"),
        "limited_edition" to listOf("limited edition"),
        "bonus_track_version" to listOf("bonus track"),
        "deluxe" to listOf("deluxe"),
        "reissue" to listOf("reissue"),
        "edition" to emptyList(),
    )

    /** A trailing `(...)`/`[...]` group, delimiter types not mixed (`"(Deluxe]"` must not match). */
    private val TRAILING_GROUP = Regex("""\s*(?:\(([^()]*)\)|\[([^\[\]]*)])\s*$""")

    /** A spaced ASCII hyphen, en dash, or em dash — never the unspaced `"Song-2012 Remaster"` form. */
    private val DASH_SEPARATOR = Regex("""\s+(?:-|–|—)\s+""")

    private val YEAR_TOKEN = Regex("""\b(19|20)\d{2}\b""")

    /**
     * Rejects `"not "`/`"non-"`/`"non "` immediately before a keyword, so `"not remastered"` doesn't
     * match `remaster`, while a genuine occurrence (`"super deluxe"` for `deluxe`) still does.
     */
    private const val NEGATION_LOOKBEHIND = """(?<!not )(?<!non-)(?<!non )"""

    private fun keywordPattern(keyword: String): Regex =
        Regex(NEGATION_LOOKBEHIND + """\b""" + Regex.escape(keyword) + """\b""", RegexOption.IGNORE_CASE)

    private val KIND_KEYWORD_PATTERNS: Map<String, List<Regex>> =
        KIND_KEYWORDS.mapValues { (_, keywords) -> keywords.map(::keywordPattern) }

    /** Whole-string kind classification — the first pattern (most-specific-first) that fullmatches. */
    private fun classifyKind(phrase: String): String? =
        KIND_PATTERNS.firstOrNull { it.pattern.matches(phrase) }?.kind

    /**
     * A trailing bracket group only counts as a qualifier if EVERY `/`-, `&`-, or `,`-separated
     * sub-phrase inside it fullmatches the vocabulary — substring containment is not enough, or
     * `"(Not Remastered)"`/`"(Live / Remastered)"` get wrongly stripped. Returns null (the whole
     * group is not a qualifier) the moment any sub-phrase fails to classify.
     */
    private fun parseQualifierGroup(content: String): List<QualifierTag>? {
        val tags = mutableListOf<QualifierTag>()
        for (rawPart in content.split(Regex("[/&,]"))) {
            val part = rawPart.trim()
            val kind = classifyKind(part) ?: return null
            val year = YEAR_TOKEN.find(part)?.value
            tags.add(QualifierTag(kind, year))
        }
        return tags
    }

    /**
     * [title] itself, followed by progressively-stripped bracket candidates — one trailing
     * qualifier group removed at a time, stopping as soon as a trailing group doesn't
     * whole-group-conform — followed by at most one dash candidate (see [dashFallbackStep]). Each
     * candidate's [FallbackCandidate.removedTags] holds only the tags peeled off to reach it.
     */
    fun qualifierFallbackCandidates(title: String): List<FallbackCandidate> {
        val trimmed = title.trimEnd()
        val candidates = mutableListOf(FallbackCandidate(title, emptyList()))
        var cur = trimmed
        var removed = emptyList<QualifierTag>()
        while (true) {
            val (stripped, tags) = nextFallbackStep(cur) ?: break
            cur = stripped
            removed = tags + removed
            if (cur.isNotEmpty()) candidates.add(FallbackCandidate(cur, removed))
        }
        dashFallbackStep(trimmed)?.let { (stripped, tags) ->
            if (stripped.isNotEmpty()) candidates.add(FallbackCandidate(stripped, tags))
        }
        return candidates
    }

    /**
     * Whether [title] ends in a bracketed group at all, whatever it says — the *shape* a qualifier
     * takes, as opposed to [qualifierFallbackCandidates]'s vocabulary, which strips only the groups
     * naming a reissue or pressing. A caller that must not assume an unclassifiable group is absent
     * asks this instead: `"(Live at Earls Court)"` conforms to nothing above, and is exactly the
     * kind of group [MusicBrainzApi.searchCanonicalRecordings] must not filter against.
     */
    fun hasTrailingGroup(title: String): Boolean = TRAILING_GROUP.containsMatchIn(title)

    /**
     * One stripping step: [cur] with its trailing qualifier group removed, and that group's tags —
     * or null if there is no trailing group or it doesn't conform (an empty [cur] included, which is
     * what ends the loop in [qualifierFallbackCandidates]).
     */
    private fun nextFallbackStep(cur: String): Pair<String, List<QualifierTag>>? {
        val match = TRAILING_GROUP.find(cur) ?: return null
        val content = match.groups[1]?.value ?: match.groups[2]?.value ?: return null
        val tags = parseQualifierGroup(content) ?: return null
        return cur.substring(0, match.range.first).trimEnd() to tags
    }

    /**
     * [title] with its last [DASH_SEPARATOR] group removed, and that suffix's tags — or null if
     * there is no spaced dash or the whole suffix doesn't conform. Peels at most one group and is
     * never reapplied to its own output, unlike [nextFallbackStep]'s bracket loop: `"Song - Mix -
     * 2012 Remaster"` may yield `"Song - Mix"`, never `"Song"`. The *last* dash is what makes that
     * true — a preceding, identity-bearing suffix is left in the stripped base rather than eaten.
     */
    private fun dashFallbackStep(title: String): Pair<String, List<QualifierTag>>? {
        val match = DASH_SEPARATOR.findAll(title).lastOrNull() ?: return null
        val suffix = title.substring(match.range.last + 1).trim()
        val tags = parseQualifierGroup(suffix) ?: return null
        return title.substring(0, match.range.first).trimEnd() to tags
    }

    private val WHITESPACE = Regex("""\s+""")

    /** Normalize for title/artist equality checks: lowercase, collapsed whitespace, straight quotes. */
    fun normalize(value: String?): String =
        value.orEmpty().trim().lowercase().replace(WHITESPACE, " ").replace('’', '\'')

    /**
     * How strongly a candidate's disambiguation/title [text] supports [tag]:
     * - `0` — the tag's kind keyword isn't present at all (or the tag is the weight-zero `edition`
     *   kind, which never scores above 0 on its own).
     * - `1` — the kind matched, but [text] states a year different from [QualifierTag.year] —
     *   conflicting evidence, weaker than no year stated at all.
     * - `2` — the kind matched and [text] states no year (unknown, not conflicting).
     * - `3` — the kind matched and [text]'s year is exactly [QualifierTag.year].
     */
    private fun tagMatchTier(text: String, tag: QualifierTag): Int {
        val keywords = KIND_KEYWORD_PATTERNS[tag.kind] ?: return 0
        if (keywords.isEmpty()) return 0
        val normText = normalize(text)
        if (keywords.none { it.containsMatchIn(normText) }) return 0
        val requestedYear = tag.year ?: return 2
        val yearsInText = YEAR_TOKEN.findAll(normText).map { it.value }.toList()
        return when {
            requestedYear in yearsInText -> 3
            yearsInText.isNotEmpty() -> 1
            else -> 2
        }
    }

    /**
     * How well [text] evidences every tag in [tags], as the summed [tagMatchTier] — so multiple
     * matching tags outrank one, and an exact year outranks a kind-only match. `0` when [tags] is
     * empty, which makes it inert for a caller that stripped no qualifier.
     */
    fun tagEvidence(text: String, tags: List<QualifierTag>): Int =
        tags.sumOf { tagMatchTier(text, it) }
}
