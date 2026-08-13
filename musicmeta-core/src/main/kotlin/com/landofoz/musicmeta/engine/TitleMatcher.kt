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
     * Album-selection tiers, ordered worst to best so [TitleTier.NONE] sorts lowest and a
     * provider's own acceptance policy can rank a filtered pool by natural (enum) order. Shared
     * vocabulary only — which tiers a provider admits, and on what evidence, is that provider's
     * own policy (`docs/pitfalls.md` §7).
     */
    internal enum class TitleTier { NONE, EDITION, EXACT }

    fun equivalent(a: String, b: String): Boolean = parse(a) == parse(b)

    /**
     * A qualifier is edition decoration, not a distinct release, when it names nothing but a
     * remaster: `"2015 Remaster"`, `"Remastered"`, `"2016 Remastered Version"`. Anything else —
     * `"Live"`, `"Deluxe"`, `"Remix"`, `"Anniversary Edition"`, a box-set description — names a
     * different release and is never classified as edition decoration. Whether a provider's
     * acceptance policy tolerates this tier at all is that provider's own decision.
     */
    internal fun isEditionDecoration(qualifier: String): Boolean = EDITION_DECORATION_REGEX.matches(qualifier)

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
