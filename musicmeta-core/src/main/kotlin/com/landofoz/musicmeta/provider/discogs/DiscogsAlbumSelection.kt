package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher

/**
 * The artist/title Discogs's combined `"Artist - Title"` search field names, or null if no ` - `
 * boundary in [combined] has an artist-side [ArtistMatcher] accepts for [requestedArtist].
 *
 * Discogs exposes the two halves as one display field, not separate structured ones, and neither
 * artist nor album names are dash-free in general, so a single fixed split (first or last ` - `)
 * can truncate either half. Trying each ` - ` boundary left to right and keeping the first whose
 * disambiguator-stripped artist-side matches [requestedArtist] finds the real boundary without
 * assuming one: a too-short artist-side is walked past, and everything after the accepted boundary
 * — including a title that itself contains ` - ` — stays intact as the album title.
 */
internal fun parseDiscogsRelease(combined: String, requestedArtist: String): Pair<String, String>? {
    var from = 0
    while (true) {
        val index = combined.indexOf(" - ", from)
        if (index < 0) return null
        val artistPart = stripDiscogsDisambiguator(combined.substring(0, index).trim())
        val titlePart = combined.substring(index + 3).trim()
        if (artistPart.isNotEmpty() && titlePart.isNotEmpty() && ArtistMatcher.isMatch(requestedArtist, artistPart)) {
            return artistPart to titlePart
        }
        from = index + 3
    }
}

/**
 * Ranks a `/database/search` pool for [request]: parse each candidate's combined title safely,
 * keep only those whose parsed title [TitleMatcher.equivalent]s the request, then order survivors
 * by artist quality and — only when the request supplies a year — a matching release year.
 * `maxWithOrNull` keeps the first maximum, so provider order is the final tie-break for free
 * (`docs/pitfalls.md` §7).
 *
 * Acceptance stops at full-title equivalence: Discogs's pressing search has no measured
 * provider-decoration tier the way Deezer/iTunes's remaster suffix does, so a bare request here
 * never admits a qualified candidate.
 */
internal fun List<DiscogsRelease>.selectRelease(request: EnrichmentRequest.ForAlbum): DiscogsRelease? =
    mapNotNull { release ->
        val (artist, title) = parseDiscogsRelease(release.title, request.artist) ?: return@mapNotNull null
        if (!TitleMatcher.equivalent(request.title, title)) return@mapNotNull null
        Triple(release, artist, title)
    }.maxWithOrNull(
        compareBy(
            { ArtistMatcher.matchQuality(request.artist, it.second) },
            { request.year != null && it.first.year == request.year.toString() },
        ),
    )?.first
