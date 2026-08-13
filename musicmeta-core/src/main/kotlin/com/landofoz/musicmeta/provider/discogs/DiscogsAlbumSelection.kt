package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher

/**
 * The artist/title Discogs's combined `"Artist - Title"` search field names, or null if no ` - `
 * boundary in [combined] has a disambiguator-stripped artist-side [ArtistMatcher] accepts for
 * [requestedArtist].
 *
 * Discogs exposes the two halves as one display field, not separate structured ones, and neither
 * artist nor album names are dash-free in general, so a single fixed split (first or last ` - `)
 * can truncate either half — and stopping at the *first* boundary whose artist-side merely passes
 * [ArtistMatcher]'s deliberately loose floor is not enough either: for an artist that itself
 * contains ` - ` (`"Artist - Name"`), the first boundary's short artist-side ("Artist") can still
 * pass that floor by partial match, well short of the real split. Every boundary is tried; a
 * boundary whose artist-side matches [requestedArtist] **and** whose title-side [TitleMatcher.equivalent]s
 * [requestedTitle] wins outright as the real split. Only when no boundary clears both sides does the
 * first artist-only match stand in, so a legitimate title-qualifier mismatch is still handed to the
 * caller's own acceptance check downstream rather than silently dropped here.
 */
internal fun parseDiscogsRelease(
    combined: String,
    requestedArtist: String,
    requestedTitle: String,
): Pair<String, String>? {
    var from = 0
    var artistOnlyMatch: Pair<String, String>? = null
    while (true) {
        val index = combined.indexOf(" - ", from)
        if (index < 0) return artistOnlyMatch
        val artistPart = stripDiscogsDisambiguator(combined.substring(0, index).trim())
        val titlePart = combined.substring(index + 3).trim()
        if (artistPart.isNotEmpty() && titlePart.isNotEmpty() && ArtistMatcher.isMatch(requestedArtist, artistPart)) {
            if (TitleMatcher.equivalent(requestedTitle, titlePart)) return artistPart to titlePart
            if (artistOnlyMatch == null) artistOnlyMatch = artistPart to titlePart
        }
        from = index + 3
    }
}

/**
 * A release [selectRelease] accepted, with the evidence that ranked it: the parsed [artist] and
 * [title] halves of the combined search field, [artistQuality] from [ArtistMatcher.matchQuality],
 * and [tier] from [TitleMatcher] — always [TitleMatcher.TitleTier.EXACT], since Discogs acceptance
 * stops at full-title equivalence and admits no lower tier.
 */
internal data class DiscogsAlbumChoice(
    val release: DiscogsRelease,
    val artist: String,
    val title: String,
    val artistQuality: Int,
    val tier: TitleMatcher.TitleTier,
)

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
internal fun List<DiscogsRelease>.selectRelease(request: EnrichmentRequest.ForAlbum): DiscogsAlbumChoice? =
    mapNotNull { release ->
        val (artist, title) = parseDiscogsRelease(release.title, request.artist, request.title)
            ?: return@mapNotNull null
        if (!TitleMatcher.equivalent(request.title, title)) return@mapNotNull null
        DiscogsAlbumChoice(
            release = release,
            artist = artist,
            title = title,
            artistQuality = ArtistMatcher.matchQuality(request.artist, artist),
            tier = TitleMatcher.TitleTier.EXACT,
        )
    }.maxWithOrNull(
        compareBy(
            { it.artistQuality },
            { request.year != null && it.release.year == request.year.toString() },
        ),
    )
