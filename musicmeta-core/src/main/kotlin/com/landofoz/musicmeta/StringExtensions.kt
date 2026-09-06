package com.landofoz.musicmeta

import java.net.URI
import java.net.URISyntaxException

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

/**
 * The site this URL points at — its host, lowercased, with a leading `www.` dropped — or null when
 * no host can be read from it.
 *
 * Nothing else is removed: `open.spotify.com` and `m.example.com` name different things to the
 * host that serves them, so collapsing a subdomain would answer for a site the URL does not reach.
 */
internal fun String.urlSiteLabel(): String? {
    val host = try {
        URI(this).host
    } catch (_: URISyntaxException) {
        null
    }
    return host?.lowercase()?.removePrefix("www.")?.takeIfNotEmpty()
}

private val MBID_SHAPE =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
