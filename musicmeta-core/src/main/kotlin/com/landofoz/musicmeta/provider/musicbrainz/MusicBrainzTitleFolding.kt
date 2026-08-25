package com.landofoz.musicmeta.provider.musicbrainz

/**
 * Folds a title's typographic and symbolic characters down to the plainest spelling a caller could
 * reasonably type, so `"F# A# (Infinity)"` and MusicBrainz's own `"F♯ A♯ ∞"` compare equal.
 *
 * **For comparison only — never build a query from a folded title.** MusicBrainz's analyzer folds
 * curly quotes and reads `#` as `♯` already, but relates no symbol to the word it is read as:
 * `release:"F# A# (Infinity)"` returns zero, `release:"F♯ A♯ ∞"` twelve (live, 2026-08-10). Nothing
 * folds the caller's text into `∞`, so this folds the other way and needs a candidate title in hand.
 *
 * [WORD_SYMBOLS] is a transliteration table, not a rule: only symbols with one unambiguous English
 * reading belong in it.
 */
internal object MusicBrainzTitleFolding {

    /** Symbols read as a word, spaced out on both sides so `"A♯∞"` folds to two tokens, not one. */
    private val WORD_SYMBOLS: Map<Char, String> = mapOf(
        '∞' to "infinity",
    )

    /** Symbols with a plain-keyboard equivalent, substituted in place. */
    private val CHARACTER_SYMBOLS: Map<Char, String> = mapOf(
        '♯' to "#",
        '♭' to "b",
        '×' to "x",
        '–' to "-",
        '—' to "-",
        '‐' to "-",
        '…' to "...",
    )

    /**
     * Quote marks in either curl, and the bracket a caller puts around a symbol they spelled out
     * (`"(Infinity)"`). Dropping is the only fold that works both ways round.
     */
    private const val DROPPED_CHARS = "()[]{}\"'‘’“”«»"

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Whether any differently-spelled title could [fold] to the same string as [title] — the
     * admission test for a fold-comparison fallback: when this is false, browsing a catalogue for
     * a folded match cannot succeed, because folding never edits a letter. True when [title] itself
     * folds (spellings converge on it) or when its folded form contains the image of any folded
     * symbol — deliberately over-inclusive, since a single-letter image like `b` cannot be told
     * from an ordinary letter. Dropped characters are invisible in the image (judgement: a match
     * differing only by dropped brackets or quotes is one the search analyzer already finds, so an
     * empty pool rules it out).
     */
    fun foldMatchPossible(title: String): Boolean {
        val folded = fold(title)
        if (folded != title.lowercase().trim().replace(WHITESPACE, " ")) return true
        return FOLD_IMAGES.any { folded.contains(it) }
    }

    /** What a folded symbol looks like from the plain side — derived from the tables, not listed. */
    private val FOLD_IMAGES: List<String> = (WORD_SYMBOLS.values + CHARACTER_SYMBOLS.values).distinct()

    /** [title] lowercased, folded, and whitespace-collapsed. Equality on this is the whole point. */
    fun fold(title: String): String {
        val folded = StringBuilder()
        for (char in title.lowercase()) {
            when {
                char in WORD_SYMBOLS -> folded.append(' ').append(WORD_SYMBOLS.getValue(char)).append(' ')
                char in CHARACTER_SYMBOLS -> folded.append(CHARACTER_SYMBOLS.getValue(char))
                char in DROPPED_CHARS -> Unit
                else -> folded.append(char)
            }
        }
        return folded.toString().trim().replace(WHITESPACE, " ")
    }
}
