package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.Attribution

/**
 * What `imageinfo` states about one file, as an [Attribution]. Every fact the answer carries is
 * kept, and none of them is judged: a file with no licence, an unfamiliar one, a restriction, or
 * fields that contradict each other maps to an [Attribution] holding what was said.
 *
 * - [Attribution.attributionText] is the `Attribution` field. Where Wikimedia supplies it, it
 *   replaces `Artist` plus `Credit`, and both are kept beside it.
 * - [Attribution.licence] prefers `LicenseShortName`, then `UsageTerms`, then the `License` slug.
 * - [Attribution.otherLicences] stays empty. The response gives one licence per file and no marker
 *   for a second; Wikimedia documents these fields as unreliable for multi-licensed files, so the
 *   one licence is reported as the upstream's and no further one is guessed.
 * - [Attribution.restrictions] are the `Restrictions` keywords, plus `non-free` when `NonFree` is
 *   true. Wikimedia has no field for how a file was modified, so [Attribution.modification] is null.
 *
 * Text is reduced to plain text, because Commons delivers `Artist`, `Credit` and `UsageTerms` as
 * HTML. [Attribution.sourceUrl] and [Attribution.licenceUrl] are the upstream's text as given,
 * trimmed and nothing more: an `http`, relative or otherwise unsafe URL is carried like any other,
 * and checking it before it becomes a link is the consumer's job.
 */
internal fun WikipediaFileInfo.toFileAttribution(): Attribution {
    fun text(field: String): String? = extmetadata[field]?.let(::plainText)
    return Attribution(
        title = title,
        sourceUrl = descriptionUrl?.let(::urlAsGiven),
        creator = text("Artist"),
        attributionText = text("Attribution"),
        credit = text("Credit"),
        licence = text("LicenseShortName") ?: text("UsageTerms") ?: text("License"),
        licenceUrl = extmetadata["LicenseUrl"]?.let(::urlAsGiven),
        copyrightStatus = text("Copyrighted"),
        restrictions = restrictionsOf(extmetadata),
    )
}

private fun restrictionsOf(extmetadata: Map<String, String>): List<String> {
    // `Restrictions` is an array of keywords that the Action API joins with `|`.
    val keywords = extmetadata["Restrictions"].orEmpty().split('|').mapNotNull(::plainText)
    val nonFree = extmetadata["NonFree"]?.trim().equals("true", ignoreCase = true)
    return (if (nonFree) keywords + NON_FREE else keywords).distinct()
}

private const val NON_FREE = "non-free"

/** [raw] trimmed and otherwise untouched, or null when nothing is left. */
private fun urlAsGiven(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

/**
 * The most characters of one `extmetadata` value that are read. A value past it is cut here and
 * ends in [ELLIPSIS], so a hostile or runaway value costs bounded work and the cut is visible.
 * Real `Artist`, `Credit` and `UsageTerms` values run to a few hundred characters, the Radiohead
 * gallery credit to about 1,500.
 */
internal const val MAX_FIELD_CHARS = 8192

private const val ELLIPSIS = "…"
private const val NO_BREAK_SPACE = ' '

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "copy" to "©", "ndash" to "–", "mdash" to "—",
)

private val LINE_BREAKING_TAGS = setOf(
    "br", "p", "div", "li", "ul", "ol", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6",
)

/**
 * [html] as the text a reader sees: tags removed, entities decoded once, whitespace collapsed.
 * Null when nothing is left. Decoding after the tags are gone means an encoded `&lt;b&gt;` stays
 * the literal text `<b>`, which is plain text the consumer escapes like any other.
 *
 * Every step is one pass over the characters, so the cost is linear in the value. A tag with no
 * closing `>`, a comment with no `-->`, and a `script` or `style` element with no closing tag
 * swallow the rest of the value, as a browser reads it. A `<` not followed by a letter, `/`, `!`
 * or `?` is text. A value past [MAX_FIELD_CHARS] is cut there and ends in an
 * ellipsis.
 */
private fun plainText(html: String): String? {
    val cut = html.length > MAX_FIELD_CHARS
    // A high surrogate left at the cut would be a lone half of a character.
    val end = if (cut && html[MAX_FIELD_CHARS - 1].isHighSurrogate()) MAX_FIELD_CHARS - 1 else MAX_FIELD_CHARS
    val text = collapseWhitespace(decodeEntities(stripMarkup(if (cut) html.substring(0, end) else html)))
    return if (text.isEmpty()) null else if (cut) text + ELLIPSIS else text
}

private fun stripMarkup(html: String): String {
    val out = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        if (html[i] == '<' && startsMarkup(html, i)) {
            i = consumeMarkup(html, i, out)
        } else {
            out.append(html[i])
            i++
        }
    }
    return out.toString()
}

/**
 * Handles the markup that opens at [at], appends the space it leaves (if any) to [out], and returns
 * the index to read on from: `html.length` when the markup never closes and so takes the rest.
 */
private fun consumeMarkup(html: String, at: Int, out: StringBuilder): Int {
    if (html.startsWith("<!--", at)) {
        val end = html.indexOf("-->", at + 4)
        return if (end < 0) html.length else end + 3
    }
    val closing = html.startsWith("/", at + 1)
    val nameStart = if (closing) at + 2 else at + 1
    val close = if (closing || html[nameStart].isLetter()) tagEnd(html, nameStart) else html.indexOf('>', at)
    if (close < 0) return html.length
    val name = tagName(html, nameStart, close)
    if (!closing && (name == "script" || name == "style")) {
        val after = indexAfterClosingTag(html, name, close + 1)
        if (after < 0) return html.length
        out.append(' ')
        return after
    }
    // `</br>` is no line break; only the opening form of `br` is.
    if (name in LINE_BREAKING_TAGS && !(closing && name == "br")) out.append(' ')
    return close + 1
}

/** The lower-cased name that starts at [from] in a tag that ends at [close]. */
private fun tagName(html: String, from: Int, close: Int): String {
    var end = from
    while (end < close && (html[end].isLetterOrDigit() || html[end] == '_')) end++
    return html.substring(from, end).lowercase()
}

/** As an HTML parser reads it, `<` opens markup only before an ASCII letter, `/`, `!` or `?`. */
private fun startsMarkup(html: String, at: Int): Boolean {
    val next = html.getOrNull(at + 1) ?: return false
    return next in 'a'..'z' || next in 'A'..'Z' || next == '/' || next == '!' || next == '?'
}

/**
 * The index of the `>` that ends the tag whose body starts at [from], or -1 when it never closes.
 * A quoted attribute value (`title="a>b"`) may hold a `>`.
 */
private fun tagEnd(html: String, from: Int): Int {
    var j = from
    while (j < html.length && html[j] != '>') {
        j = if (html[j] == '=') afterAttributeValue(html, j) else j + 1
        if (j < 0) return -1
    }
    return if (j < html.length) j else -1
}

/**
 * The index to read on from after the `=` at [equals]: past the closing quote of a quoted value, -1
 * when that quote is missing, else just past the `=`. A quote opens a value only straight after
 * `=`, so an apostrophe in a bare value (`title=O'Brien`) does not swallow the tag.
 */
private fun afterAttributeValue(html: String, equals: Int): Int {
    var k = equals + 1
    while (k < html.length && isCollapsibleSpace(html[k])) k++
    if (k >= html.length || (html[k] != '"' && html[k] != '\'')) return equals + 1
    val endQuote = html.indexOf(html[k], k + 1)
    return if (endQuote < 0) -1 else endQuote + 1
}

/** The index after the first `</name\s*>` at or past [from], or -1 when there is none. */
private fun indexAfterClosingTag(html: String, name: String, from: Int): Int {
    var i = html.indexOf("</", from)
    while (i >= 0) {
        if (html.regionMatches(i + 2, name, 0, name.length, ignoreCase = true)) {
            var j = i + 2 + name.length
            while (j < html.length && isCollapsibleSpace(html[j])) j++
            if (j < html.length && html[j] == '>') return j + 1
        }
        i = html.indexOf("</", i + 2)
    }
    return -1
}

private fun decodeEntities(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val entity = if (text[i] == '&') entityAt(text, i) else null
        if (entity == null) {
            out.append(text[i])
            i++
        } else {
            out.append(entity.text)
            i = entity.next
        }
    }
    return out.toString()
}

private class Entity(val text: String, val next: Int)

/** The entity that starts at the `&` at [at], or null when it is not a known, safe one. */
private fun entityAt(text: String, at: Int): Entity? {
    val bodyStart = at + 1
    val numeric = text.startsWith("#", bodyStart)
    val hex = numeric && (text.startsWith("x", bodyStart + 1) || text.startsWith("X", bodyStart + 1))
    val digitsStart = if (hex) bodyStart + 2 else if (numeric) bodyStart + 1 else bodyStart
    var bodyEnd = digitsStart
    while (bodyEnd < text.length && isEntityBodyChar(text[bodyEnd], numeric, hex)) bodyEnd++
    if (bodyEnd == digitsStart || !text.startsWith(";", bodyEnd)) return null
    val body = text.substring(digitsStart, bodyEnd)
    val decoded = if (numeric) codePointOf(body, hex)?.let { String(Character.toChars(it)) } else NAMED_ENTITIES[body]
    return decoded?.let { Entity(it, bodyEnd + 1) }
}

private fun isEntityBodyChar(c: Char, numeric: Boolean, hex: Boolean): Boolean = when {
    hex -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    numeric -> c in '0'..'9'
    else -> c in 'a'..'z' || c in 'A'..'Z'
}

/**
 * The character a numeric entity names, or null when it names none a reader should get: past
 * Unicode, a surrogate half, NUL, or a control character other than tab, line feed and carriage
 * return. The entity then stays as written.
 */
private fun codePointOf(digits: String, hex: Boolean): Int? =
    digits.toIntOrNull(if (hex) 16 else 10)?.takeIf { code ->
        Character.isValidCodePoint(code) && when (Character.getType(code).toByte()) {
            Character.SURROGATE -> false
            Character.CONTROL -> code == 0x09 || code == 0x0A || code == 0x0D
            else -> true
        }
    }

/** Runs of whitespace, no-break spaces included, become one space; the ends are trimmed. */
private fun collapseWhitespace(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) {
        if (isCollapsibleSpace(c)) {
            if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
        } else {
            out.append(c)
        }
    }
    if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
    return out.toString()
}

private fun isCollapsibleSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == NO_BREAK_SPACE
