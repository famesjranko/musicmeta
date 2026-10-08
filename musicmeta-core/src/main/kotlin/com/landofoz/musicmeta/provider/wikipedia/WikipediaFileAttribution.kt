package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.Attribution
import java.net.URI
import java.net.URISyntaxException

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
 * HTML. A URL that is not an absolute `https` URL is left out; the facts beside it stay.
 */
internal fun WikipediaFileInfo.toFileAttribution(): Attribution {
    fun text(field: String): String? = extmetadata[field]?.let(::plainText)
    return Attribution(
        title = title,
        sourceUrl = descriptionUrl?.let(::absoluteHttpsUrl),
        creator = text("Artist"),
        attributionText = text("Attribution"),
        credit = text("Credit"),
        licence = text("LicenseShortName") ?: text("UsageTerms") ?: text("License"),
        licenceUrl = extmetadata["LicenseUrl"]?.let(::absoluteHttpsUrl),
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

/** [raw] as an absolute `https` URL, or null: a relative, protocol-relative, `http` or unparsable one. */
private fun absoluteHttpsUrl(raw: String): String? {
    val url = raw.trim()
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    val isHttps = uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    return url.takeIf { isHttps }
}

private val SCRIPT_OR_STYLE = Regex(
    "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val LINE_BREAKING_TAG = Regex("<(?:br|/?(?:p|div|li|ul|ol|tr|td|th|h[1-6]))\\b[^>]*>", RegexOption.IGNORE_CASE)
private val TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
private val WHITESPACE = Regex("\\s+")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "copy" to "©", "ndash" to "–", "mdash" to "—",
)

/**
 * [html] as the text a reader sees: tags removed, entities decoded once, whitespace collapsed.
 * Null when nothing is left. Decoding after the tags are gone means an encoded `&lt;b&gt;` stays
 * the literal text `<b>`, which is plain text the consumer escapes like any other.
 */
private fun plainText(html: String): String? {
    val withoutMarkup = html
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(LINE_BREAKING_TAG, " ")
        .replace(TAG, "")
    return decodeEntities(withoutMarkup)
        .replace(' ', ' ')
        .replace(WHITESPACE, " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
    val body = match.groupValues[1]
    when {
        body.startsWith("#") -> codePointOf(body)?.let { String(Character.toChars(it)) } ?: match.value
        else -> NAMED_ENTITIES[body] ?: match.value
    }
}

private fun codePointOf(numeric: String): Int? {
    val isHex = numeric.startsWith("#x") || numeric.startsWith("#X")
    val code = if (isHex) numeric.drop(2).toIntOrNull(16) else numeric.drop(1).toIntOrNull()
    return code?.takeIf { Character.isValidCodePoint(it) }
}
