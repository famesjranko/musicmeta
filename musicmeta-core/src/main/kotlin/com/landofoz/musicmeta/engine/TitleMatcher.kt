package com.landofoz.musicmeta.engine

/**
 * Provider-independent title comparison, shared vocabulary only — each name-search provider
 * still decides its own acceptance and ranking evidence on top of this (`docs/pitfalls.md` §7).
 *
 * Two titles are [equivalent] iff their normalized base is equal and their normalized terminal
 * qualifier is equal, where "no qualifier" on both sides also counts as equal. A qualifier is one
 * terminal ` - ` (or en/em dash) spaced group, or one terminal `(...)`/`[...]` group — never a
 * bare trailing word. Cosmetic punctuation (surrounding quotes, dash character, case, whitespace)
 * is normalized before comparison; the qualifier's own text is never stripped or approximated, so
 * a qualifier present on only one side is never equivalent to its absence: "Song" and "Song
 * (Live)" stay different recordings, but "Song - Live" and "Song (Live)" name the same one.
 */
internal object TitleMatcher {

    internal data class Parts(val base: String, val qualifier: String?)

    /**
     * Album-selection tiers, ordered worst to best so [TitleTier.NONE] sorts lowest and a caller
     * can rank a filtered pool by natural (enum) order. [EDITION] exists only for a request that
     * supplied no qualifier at all: a bare album request must still be able to land the only
     * edition a provider actually returns (`docs/pitfalls.md` §7's album-selection tier example),
     * but it never lets a qualifier the caller *did* ask for collapse into a different one.
     */
    internal enum class TitleTier { NONE, EDITION, EXACT }

    fun equivalent(a: String, b: String): Boolean = parse(a) == parse(b)

    /**
     * Album-title acceptance tier for [candidate] against [requested]: [TitleTier.EXACT] when
     * [equivalent] would already accept them, [TitleTier.EDITION] when [requested] is bare and
     * [candidate]'s only qualifier is provider-added edition decoration ([isEditionDecoration]),
     * [TitleTier.NONE] otherwise. Every other candidate qualifier — live, remix, deluxe, box,
     * anniversary, soundtrack — stays identity-bearing and is never admitted by a bare request.
     */
    fun titleTier(requested: String, candidate: String): TitleTier {
        val req = parse(requested)
        val cand = parse(candidate)
        if (req.base != cand.base) return TitleTier.NONE
        if (req.qualifier == cand.qualifier) return TitleTier.EXACT
        if (req.qualifier == null && cand.qualifier != null && isEditionDecoration(cand.qualifier)) {
            return TitleTier.EDITION
        }
        return TitleTier.NONE
    }

    /**
     * A qualifier is edition decoration, not a distinct release, when it names nothing but a
     * remaster — the one provider-added suffix a bare request must tolerate (`docs/pitfalls.md`
     * §7): `"2015 Remaster"`, `"Remastered"`, `"2016 Remastered Version"`. Anything else — `"Live"`,
     * `"Deluxe"`, `"Remix"`, `"Anniversary Edition"`, a box-set description — names a different
     * release and stays outside this tier.
     */
    private fun isEditionDecoration(qualifier: String): Boolean = EDITION_DECORATION_REGEX.matches(qualifier)

    private val EDITION_DECORATION_REGEX = Regex("""^(\d{4}\s+)?remaster(ed)?(\s+version)?$""")

    internal fun parse(title: String): Parts {
        val normalized = normalize(title)
        return bracketQualifier(normalized) ?: dashQualifier(normalized) ?: Parts(normalized, null)
    }

    private fun bracketQualifier(normalized: String): Parts? {
        val match = TERMINAL_BRACKET_REGEX.matchEntire(normalized) ?: return null
        val base = match.groupValues[1].trim()
        val qualifier = match.groupValues[2].trim()
        if (base.isEmpty() || qualifier.isEmpty()) return null
        return Parts(base, qualifier)
    }

    private fun dashQualifier(normalized: String): Parts? {
        val index = normalized.lastIndexOf(" - ")
        if (index <= 0) return null
        val base = normalized.substring(0, index).trim()
        val qualifier = normalized.substring(index + 3).trim()
        if (base.isEmpty() || qualifier.isEmpty()) return null
        return Parts(base, qualifier)
    }

    private fun normalize(title: String): String {
        var s = title.trim()
        s = s.replace(QUOTE_REGEX, "")
        s = s.replace(DASH_REGEX, "-")
        s = s.lowercase()
        s = s.replace(WHITESPACE_REGEX, " ").trim()
        return s
    }

    /** Terminal bracket group only — a group anywhere else in the title stays part of the base. */
    private val TERMINAL_BRACKET_REGEX = Regex("""^(.+?)\s*[(\[]([^()\[\]]+)[)\]]$""")

    /** Straight and curly double quotes — Deezer's `"Heroes" (2017 Remaster)` decoration. */
    private val QUOTE_REGEX = Regex("[\"“”]")

    /** En dash and em dash, normalized to the ASCII hyphen the ` - ` split looks for. */
    private val DASH_REGEX = Regex("[–—]")

    private val WHITESPACE_REGEX = Regex("\\s+")
}
