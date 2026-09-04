package com.landofoz.musicmeta.provider

import java.net.URLEncoder

/**
 * Percent-encodes [value] for use as a query-parameter value. `HttpClient` takes the URL already
 * encoded, so every value a `*Api` splices into a query — a name, an identifier, a key a consumer
 * supplied — goes through this, and a delimiter that belongs to the template is written literally
 * beside it.
 */
internal fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * Percent-encodes [value] for use as one path segment. Form encoding writes a space as `+`, which a
 * path reads as a literal plus rather than a space, so that one substitution is undone here. A query
 * value may take this form too — MediaWiki's `titles` parameter is given it.
 */
internal fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
