package com.landofoz.musicmeta.provider.musicbrainz

/**
 * Candidate generation and release/recording selection for the qualifier-fallback search fix
 * (`.scratch/mb-search-parenthetical-qualifiers/issues/01-album-title-qualifier-kills-search.md`).
 *
 * A provider-sourced title routinely carries a reissue/edition qualifier
 * (`"Master Of Puppets (Remastered)"`) that MusicBrainz's own release title does not literally
 * contain, so the existing exact-quoted-Lucene search comes back empty even though the album
 * exists. Some MusicBrainz titles legitimately DO contain edition wording though
 * (`"2112 (deluxe edition)"`/Rush, `"Abbey Road (anniversary edition)"`/The Beatles, both verified
 * live) — so the fix cannot strip first; it must try the caller's exact title, and only fall back
 * to a progressively-stripped candidate when the exact title comes up empty.
 *
 * This also reopens the `docs/pitfalls.md` §7 trap ("a search API's hit 0 is a ranking, not an
 * answer") for releases specifically: a bare, qualifier-free title like `"Master Of Puppets"`
 * routinely ties 25+ distinct releases at MusicBrainz's own maximum score, and once the fallback
 * makes that tie reachable via a confident match (instead of failing outright), which release wins
 * the tie becomes newly consumer-visible ([MusicBrainzMapper.toAlbumIdentifiers] propagates the
 * concrete release's date, label, country, barcode, and both MBIDs downstream). [pickBestMatch]
 * settles that tie using the qualifier text that was actually stripped to reach the resolving
 * candidate — free evidence of the caller's edition intent that would otherwise be discarded once
 * it had served its purpose in the search fallback — never overriding MusicBrainz's own score, only
 * ranking within a same-score tier, and falling back to today's existing (unmodified) pool order
 * when no qualifier evidence distinguishes the tie. General release ranking for an ordinary,
 * qualifier-free title is explicitly out of scope
 * (`.scratch/musicbrainz-release-ranking/issues/01-choose-deterministically-among-tied-releases.md`)
 * — this tie-break only ever fires with qualifier tags already in hand as a byproduct of the
 * fallback search, and does nothing for a title that never triggers it.
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
     * Deliberately excludes `live`/`mono`/`stereo`/`edit`/`version`/`explicit` — those can name a
     * genuinely different edition (not just a different pressing of the same one), so stripping them
     * risks a confident match to the *wrong* release rather than just missing a reissue. Order
     * matters: more specific kinds are tried first (`super_deluxe`/`deluxe_box_set` before bare
     * `deluxe`) so a two-word phrase classifies as the specific kind, not the generic one.
     */
    private val KIND_PATTERNS: List<KindPattern> = listOf(
        KindPattern("remaster", Regex("""(\d{4}\s+)?remaster(ed)?(\s+\d{4})?""", RegexOption.IGNORE_CASE)),
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
     * NOT bare words. `edition` is intentionally absent from every other kind's keyword list (it
     * would otherwise let the single generic word "edition" cross-match any release whose
     * disambiguation happens to contain it — reproduced live pre-fix: a `"special edition"` release
     * beat the correct `"legacy edition"` one purely because "edition" matched first) and carries an
     * empty list itself, so it can never be an independent positive signal at all.
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

    private val YEAR_TOKEN = Regex("""\b(19|20)\d{2}\b""")

    /**
     * Matching `"not "`/`"non-"`/`"non "` immediately before a keyword, so a negation isn't read as
     * the positive signal it negates (`"unremastered"`/`"not remastered"` must not match `remaster`;
     * `"non-deluxe"` must not match `deluxe`) while a genuine whole-word occurrence still matches
     * (`"super deluxe"` contains `deluxe` as a real word, not a negation, and is allowed through).
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
     * [title] itself, followed by progressively-stripped fallback candidates — most-specific first,
     * one trailing qualifier group removed at a time, stopping as soon as a trailing group doesn't
     * whole-group-conform (so an unrelated leading parenthetical, or a mixed non-conforming group,
     * is never touched). Each candidate's [FallbackCandidate.removedTags] accumulates only the tags
     * actually peeled off to reach it — a title stripped in two steps does not offer the
     * first-stripped tag as evidence for a candidate that only needed the second strip.
     */
    fun qualifierFallbackCandidates(title: String): List<FallbackCandidate> {
        val candidates = mutableListOf(FallbackCandidate(title, emptyList()))
        var cur = title.trimEnd()
        var removed = emptyList<QualifierTag>()
        while (true) {
            val (stripped, tags) = nextFallbackStep(cur) ?: break
            cur = stripped
            removed = tags + removed
            if (cur.isNotEmpty()) candidates.add(FallbackCandidate(cur, removed))
        }
        return candidates
    }

    /**
     * One stripping step: [cur] with its trailing qualifier group removed, and that group's tags —
     * or null if [cur] has no trailing group, or its trailing group doesn't whole-group-conform to
     * the vocabulary (an empty [cur] also naturally returns null here, ending the loop in
     * [qualifierFallbackCandidates] without a second exit condition).
     */
    private fun nextFallbackStep(cur: String): Pair<String, List<QualifierTag>>? {
        val match = TRAILING_GROUP.find(cur) ?: return null
        val content = match.groups[1]?.value ?: match.groups[2]?.value ?: return null
        val tags = parseQualifierGroup(content) ?: return null
        return cur.substring(0, match.range.first).trimEnd() to tags
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
     * The best of [matches] by [scoreOf] primary, summed [tagMatchTier] (across every tag in
     * [removedTags], against [textOf]) breaking a tie only — the same `maxWithOrNull(compareBy(...)
     * .thenBy(...))` shape as [pickBestRecording]/`bestArtistMatch`, where the primary comparator
     * alone decides unless it ties. [scoreOf] is lexicographically primary, so qualifier evidence can
     * never override a higher score, only rank within one; combined evidence (multiple tags matching
     * one candidate) outranks a single match. When [removedTags] is empty (the direct, first-try hit
     * needed no fallback), every candidate's tag-tier sum is equally `0`, so the comparator reduces
     * to [scoreOf] alone and `maxWithOrNull` keeping the first maximum preserves the pool's existing
     * order, unchanged from before this fallback existed.
     */
    fun <T> pickBestMatch(
        matches: List<T>,
        removedTags: List<QualifierTag>,
        scoreOf: (T) -> Int,
        textOf: (T) -> String,
    ): T? = matches.maxWithOrNull(
        compareBy<T> { scoreOf(it) }.thenBy { candidate -> removedTags.sumOf { tagMatchTier(textOf(candidate), it) } },
    )
}
