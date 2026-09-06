package com.landofoz.musicmeta

/** Returns this string if it's not blank, or null otherwise. */
internal fun String.takeIfNotEmpty(): String? = takeIf { it.isNotBlank() }

/**
 * Whether this is a MusicBrainz id, lowercased — the UUID shape and nothing else.
 *
 * One home because two callers must agree: the similar-artist batch counts how many ids it may ask
 * about, and `MusicBrainzApi` builds the `arid:` terms. A cap counted over ids the query then drops
 * would silently ask about fewer artists than it was allowed to.
 */
internal fun String.isMusicBrainzIdShape(): Boolean = MBID_SHAPE.matches(this)

private val MBID_SHAPE =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
