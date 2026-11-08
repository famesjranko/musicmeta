package com.landofoz.musicmeta.engine

/** Search-only typography folding; identity resolution retains [TitleMatcher]'s stricter policy. */
internal object SearchTitleMatcher {
    fun equivalent(a: String, b: String): Boolean = parse(a) == parse(b)

    fun parse(title: String): TitleMatcher.Parts = TitleMatcher.parse(foldTypography(title))

    fun foldTypography(value: String): String =
        value.replace('‘', '\'').replace('’', '\'').replace('′', '\'').replace('ʼ', '\'')
            .replace('“', '"').replace('”', '"')
            .replace('‐', '-').replace('‑', '-').replace("…", "...")
}
