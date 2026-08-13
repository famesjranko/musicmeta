package com.landofoz.musicmeta.engine

/**
 * One candidate this call accepted, with the evidence that ranked it: [tier] from the caller's own
 * title policy, [artistQuality] from [ArtistMatcher.matchQuality], and [tieBreaks] — the value of
 * each caller-supplied tie-break on this candidate, keyed by the name given to [acceptAndRankAlbum].
 * Entries rank in the order [acceptAndRankAlbum] received them, so the first key on which two
 * candidates differ is the one that decided between them.
 */
internal data class AlbumMatch<T>(
    val candidate: T,
    val tier: TitleMatcher.TitleTier,
    val artistQuality: Int,
    val tieBreaks: Map<String, Boolean>,
)

/**
 * The accept-then-rank skeleton every name-search album selector needs: keep candidates
 * [ArtistMatcher] accepts for [requestedArtist] and whose [titleTierOf] clears [TitleMatcher.TitleTier.NONE],
 * then rank survivors by tier, then artist quality, then each of [tieBreaks] in the order given.
 * `maxWithOrNull` keeps the first maximum, so provider order is the final tie-break for free
 * (`docs/pitfalls.md` §7).
 *
 * [titleTierOf] is the caller's own acceptance policy, not a shared one — this skeleton carries no
 * opinion on which qualifiers a bare request may tolerate; that stays with the provider that calls it.
 */
internal fun <T> Iterable<T>.acceptAndRankAlbum(
    requestedArtist: String,
    artistNameOf: (T) -> String,
    titleTierOf: (T) -> TitleMatcher.TitleTier,
    vararg tieBreaks: Pair<String, (T) -> Boolean>,
): AlbumMatch<T>? {
    val matches = filter { ArtistMatcher.isMatch(requestedArtist, artistNameOf(it)) }
        .map { candidate ->
            AlbumMatch(
                candidate = candidate,
                tier = titleTierOf(candidate),
                artistQuality = ArtistMatcher.matchQuality(requestedArtist, artistNameOf(candidate)),
                tieBreaks = tieBreaks.associate { (name, tieBreak) -> name to tieBreak(candidate) },
            )
        }
        .filter { it.tier != TitleMatcher.TitleTier.NONE }

    var comparator = compareBy<AlbumMatch<T>> { it.tier }.thenBy { it.artistQuality }
    for ((name, _) in tieBreaks) {
        comparator = comparator.thenBy { it.tieBreaks.getValue(name) }
    }
    return matches.maxWithOrNull(comparator)
}
