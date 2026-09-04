package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.AlbumEvidence
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.NameMatchTier
import com.landofoz.musicmeta.engine.ProbeTrace
import com.landofoz.musicmeta.engine.TieBreakEvidence
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.artistNameTier
import com.landofoz.musicmeta.engine.resolvedAliasPool

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
    aliases: List<AlternativeName> = emptyList(),
): Pair<String, String>? {
    var from = 0
    var artistOnlyMatch: Pair<String, String>? = null
    while (true) {
        val index = combined.indexOf(" - ", from)
        if (index < 0) return artistOnlyMatch
        val artistPart = stripDiscogsDisambiguator(combined.substring(0, index).trim())
        val titlePart = combined.substring(index + 3).trim()
        val accepted = artistNameTier(requestedArtist, artistPart, aliases) != null
        if (artistPart.isNotEmpty() && titlePart.isNotEmpty() && accepted) {
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
    val tieBreaks: AlbumEvidence,
    /**
     * How [artist] matched the requested name — [NameMatchTier.CANONICAL] for the requested name
     * itself, an alias tier when this call's alias pool is what verified it. Scales the confidence.
     */
    val nameTier: NameMatchTier = NameMatchTier.CANONICAL,
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
internal suspend fun List<DiscogsRelease>.selectRelease(request: EnrichmentRequest.ForAlbum): DiscogsAlbumChoice? =
    selectRelease(request, aliases = emptyList())
        ?: selectRelease(request, aliases = resolvedAliasPool())

private fun List<DiscogsRelease>.selectRelease(
    request: EnrichmentRequest.ForAlbum,
    aliases: List<AlternativeName>,
): DiscogsAlbumChoice? =
    mapNotNull { release ->
        val (artist, title) = parseDiscogsRelease(release.title, request.artist, request.title, aliases)
            ?: return@mapNotNull null
        if (!TitleMatcher.equivalent(request.title, title)) return@mapNotNull null
        DiscogsAlbumChoice(
            release = release,
            artist = artist,
            title = title,
            artistQuality = ArtistMatcher.matchQuality(request.artist, artist),
            nameTier = artistNameTier(request.artist, artist, aliases) ?: NameMatchTier.CANONICAL,
            tier = TitleMatcher.TitleTier.EXACT,
            tieBreaks = AlbumEvidence.of(
                listOf(TieBreakEvidence("year", request.year != null && release.year == request.year.toString())),
            ),
        )
    }.maxWithOrNull(
        compareBy(
            { it.artistQuality },
            { it.tieBreaks["year"] },
        ),
    )?.also { ProbeTrace.picked("discogsRelease", it.artist, it.release.toString()) }
