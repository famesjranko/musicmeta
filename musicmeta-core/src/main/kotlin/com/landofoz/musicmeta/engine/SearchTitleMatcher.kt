package com.landofoz.musicmeta.engine

/** Search-only typography folding; identity resolution retains [TitleMatcher]'s stricter policy. */
internal object SearchTitleMatcher {
    fun equivalent(a: String, b: String): Boolean = parse(a) == parse(b)

    fun punctuationEquivalent(a: String, b: String): Boolean {
        val left = parse(a)
        val right = parse(b)
        if (left.qualifier != right.qualifier) return false

        val leftBase = stripPunctuation(left.base)
        val rightBase = stripPunctuation(right.base)
        return hasLetterOrNumber(leftBase) && hasLetterOrNumber(rightBase) && leftBase == rightBase
    }

    fun parse(title: String): TitleMatcher.Parts = TitleMatcher.parse(foldTypography(title))

    fun foldTypography(value: String): String =
        value.replace('‘', '\'').replace('’', '\'').replace('′', '\'').replace('ʼ', '\'')
            .replace('“', '"').replace('”', '"')
            .replace('‐', '-').replace('‑', '-').replace("…", "...")

    private fun stripPunctuation(value: String): String =
        value.replace(PUNCTUATION_REGEX, "").replace(WHITESPACE_REGEX, " ").trim()

    private fun hasLetterOrNumber(value: String): Boolean = value.codePoints().anyMatch { codePoint ->
        Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt(),
            -> true
            else -> false
        }
    }

    private val PUNCTUATION_REGEX = Regex("\\p{P}")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
