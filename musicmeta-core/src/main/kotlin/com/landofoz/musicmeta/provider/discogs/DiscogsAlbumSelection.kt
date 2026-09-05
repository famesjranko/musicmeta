package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.AlbumEvidence
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.NameMatchTier
import com.landofoz.musicmeta.engine.TieBreakEvidence
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.artistNameTier
import com.landofoz.musicmeta.engine.resolvedAliasPool
import com.landofoz.musicmeta.engine.sameNameTier

/**
 * The artist/title Discogs's combined `"Artist - Title"` search field names, or null if no ` - `
 * boundary in [combined] has a disambiguator-stripped artist-side [creditNameTier] accepts for
 * [requestedArtist]. The artist side is returned whole, credit and all, not narrowed to the artist
 * the request named.
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
        val accepted = creditNameTier(requestedArtist, artistPart, aliases) != null
        if (artistPart.isNotEmpty() && titlePart.isNotEmpty() && accepted) {
            if (TitleMatcher.equivalent(requestedTitle, titlePart)) return artistPart to titlePart
            if (artistOnlyMatch == null) artistOnlyMatch = artistPart to titlePart
        }
        from = index + 3
    }
}

/** Discogs joins the artists sharing a release credit with this in the one field it publishes. */
private const val CREDIT_SEPARATOR = ", "

/**
 * How [requestedArtist] matched [credit], or null if no artist [credit] names is theirs.
 *
 * A release credited to more than one artist reaches here as one string —
 * `Χάρις Αλεξίου, Αντώνης Βαρδής` — because Discogs publishes the credit only as the artist half of
 * its combined display field. A request naming one of those artists is otherwise compared against a
 * name no upstream holds for anyone, which is why every candidate for such a release was refused.
 *
 * The whole credit is tested first, so splitting can never displace a match the credit already has,
 * and an act whose own name holds a comma (`Earth, Wind & Fire`) is matched as itself. Only `", "`
 * separates: an artist name holds `&` far more often than a Discogs credit joins on it.
 *
 * A part is accepted at same name only ([sameNameTier]). The tier is then the one that part earns,
 * undemoted — this path identifies a *release*, and a release credited to the requested artist and
 * another is still the release the request named. How many artists share the credit is
 * [DiscogsAlbumChoice.artistQuality]'s to rank: measured on the whole credit, it already puts a
 * sole credit above a shared one.
 */
private fun creditNameTier(
    requestedArtist: String,
    credit: String,
    aliases: List<AlternativeName>,
): NameMatchTier? {
    artistNameTier(requestedArtist, credit, aliases)?.let { return it }
    val credited = credit.split(CREDIT_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    if (credited.size < 2) return null
    return credited.mapNotNull { sameNameTier(requestedArtist, it, aliases) }.minByOrNull { it.ordinal }
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
     * itself or for one artist of a shared credit, an alias tier when this call's alias pool is what
     * verified it. Scales the confidence.
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
            nameTier = creditNameTier(request.artist, artist, aliases) ?: NameMatchTier.CANONICAL,
            tier = TitleMatcher.TitleTier.EXACT,
            tieBreaks = AlbumEvidence.of(
                listOf(TieBreakEvidence("year", request.year != null && release.year == request.year.toString())),
            ),
        )
    }.maxWithOrNull(
        compareBy(
            // Tier leads: every alias match is the same artist quality, so without it a search-hint
            // match would beat a published-name one on Discogs's own result order.
            { it.nameTier.confidenceFactor },
            { it.artistQuality },
            { it.tieBreaks["year"] },
        ),
    )
