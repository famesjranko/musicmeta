package com.landofoz.musicmeta.provider.wikipedia

import java.net.URI
import java.net.URISyntaxException

/** Normalizes untrusted HTML metadata into bounded plain text and separately validates links. */
internal object WikipediaMetadata {
    fun plainText(value: String): String? {
        if (value.length > MAX_METADATA_LENGTH) return null
        val withoutActiveContent = value.replace(ACTIVE_CONTENT, " ").replace(COMMENTS, " ")
        val decoded = ENTITY.replace(stripMarkup(withoutActiveContent)) { match -> decodeEntity(match.groupValues[1]) }
        return decoded.map { if (it in "<>" || unsafeCharacter(it)) ' ' else it }
            .joinToString("").replace(Regex("\\s+"), " ").trim().takeIf(String::isNotBlank)
    }

    private fun stripMarkup(value: String): String {
        val text = StringBuilder()
        var position = 0
        while (position < value.length) {
            val char = value[position]
            if (char != '<') {
                text.append(char)
                position++
                continue
            }
            var quote: Char? = null
            position++
            while (position < value.length) {
                val tagChar = value[position++]
                if (quote == tagChar) quote = null
                else if (quote == null && (tagChar == '\'' || tagChar == '"')) quote = tagChar
                else if (quote == null && tagChar == '>') break
            }
            text.append(' ')
        }
        return text.toString()
    }

    fun httpsUrl(value: String): Boolean {
        if (value.any(::unsafeCharacter)) {
            return false
        }
        if (ENCODED_CONTROL.containsMatchIn(value)) return false
        return try {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
                uri.rawUserInfo == null && uri.port in -1..65535
        } catch (_: URISyntaxException) {
            false
        }
    }

    fun boolean(value: String?): Boolean? = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }

    private fun decodeEntity(entity: String): String {
        val number = when {
            entity.startsWith("#x", ignoreCase = true) -> entity.substring(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.substring(1).toIntOrNull()
            else -> null
        }
        if (number != null) {
            return if (Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF) {
                String(Character.toChars(number))
            } else " "
        }
        return HTML_ENTITIES[entity.lowercase()] ?: "&$entity;"
    }

    private fun unsafeCharacter(char: Char): Boolean =
        char.isISOControl() || char.isWhitespace() || Character.getType(char) == Character.FORMAT.toInt()

    private val HTML_ENTITIES = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ", "lt" to " ", "gt" to " ",
        "copy" to "©", "ndash" to "–", "mdash" to "—",
    )
    private const val MAX_METADATA_LENGTH = 32_768
    private val ACTIVE_CONTENT = Regex("(?is)<(script|style)\\b[^>]*>.*?(</\\1\\s*>|$)")
    private val COMMENTS = Regex("(?s)<!--.*?(-->|$)")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val ENCODED_CONTROL = Regex("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|25(?:0[0-9a-f]|1[0-9a-f]|7f))")
}
