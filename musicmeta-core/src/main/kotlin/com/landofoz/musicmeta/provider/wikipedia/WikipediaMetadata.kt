package com.landofoz.musicmeta.provider.wikipedia

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
        if (hasEncodedControl(value)) return false
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

    private fun isHexDigit(char: Char): Boolean =
        char in '0'..'9' || char.lowercaseChar() in 'a'..'f'

    private fun hasEncodedControl(value: String): Boolean {
        val decodedPercent = ENCODED_PERCENT.replace(value, "%")
        for (match in ENCODED_CONTROL.findAll(decodedPercent)) {
            val byte = match.value.substring(1, 3).toInt(16)
            if (byte <= 0x1F || byte == 0x7F || encodedC1ByteIsUnsafe(decodedPercent, match.range.first)) {
                return true
            }
        }
        return false
    }

    private fun encodedC1ByteIsUnsafe(value: String, percentIndex: Int): Boolean {
        val byte = value.substring(percentIndex + 1, percentIndex + 3).toInt(16)
        if (byte !in 0x80..0x9F) return false

        var start = percentIndex
        while (start >= 3 && value[start - 3] == '%' &&
            value.substring(start - 2, start).all(::isHexDigit)
        ) {
            start -= 3
        }
        var end = percentIndex + 3
        while (end + 2 < value.length && value[end] == '%' &&
            value.substring(end + 1, end + 3).all(::isHexDigit)
        ) {
            end += 3
        }
        val bytes = (start until end step 3).map {
            value.substring(it + 1, it + 3).toInt(16).toByte()
        }.toByteArray()
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                .any(Char::isISOControl)
        } catch (_: CharacterCodingException) {
            true
        }
    }

    private val HTML_ENTITIES = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ", "lt" to " ", "gt" to " ",
        "copy" to "©", "ndash" to "–", "mdash" to "—",
    )
    private const val MAX_METADATA_LENGTH = 32_768
    private val ACTIVE_CONTENT = Regex("(?is)<(script|style)\\b[^>]*>.*?(</\\1\\s*>|$)")
    private val COMMENTS = Regex("(?s)<!--.*?(-->|$)")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val ENCODED_PERCENT = Regex("(?i)%25")
    private val ENCODED_CONTROL = Regex(
        "(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|[89][0-9a-f]|25(?:0[0-9a-f]|1[0-9a-f]|7f|[89][0-9a-f]))",
    )
}
