package com.landofoz.musicmeta.engine

/** One caller-named tie-break's recorded value for a candidate [acceptAndRankAlbum] accepted. */
internal data class TieBreakEvidence(val name: String, val value: Boolean)

/**
 * The tie-break evidence [acceptAndRankAlbum] recorded for one accepted candidate, in the order the
 * caller supplied the tie-breaks — the order two candidates are compared in once tier and artist
 * quality tie. Construction rejects a duplicate name rather than silently collapsing it, which a
 * `Map<String, Boolean>` would do.
 */
internal class AlbumEvidence private constructor(private val ordered: List<TieBreakEvidence>) :
    List<TieBreakEvidence> by ordered {

    /** The value named [name] recorded for this candidate. */
    operator fun get(name: String): Boolean = ordered.first { it.name == name }.value

    override fun equals(other: Any?): Boolean = other is AlbumEvidence && ordered == other.ordered
    override fun hashCode(): Int = ordered.hashCode()
    override fun toString(): String = "AlbumEvidence($ordered)"

    internal companion object {
        fun of(entries: List<TieBreakEvidence>): AlbumEvidence {
            val names = entries.map { it.name }
            require(names.size == names.toSet().size) { "duplicate tie-break name(s): $names" }
            return AlbumEvidence(entries)
        }
    }
}

/** One named tie-break a caller of [acceptAndRankAlbum] wants evaluated, in comparison order. */
internal data class AlbumTieBreak<T>(val name: String, val predicate: (T) -> Boolean)

/**
 * One candidate this call accepted, with the evidence that ranked it: [tier] from the caller's own
 * title policy, [artistQuality] from [ArtistMatcher.matchQuality], and [tieBreaks] — the value of
 * each caller-supplied tie-break on this candidate, in the order [acceptAndRankAlbum] received them.
 * The first tie-break on which two candidates differ is the one that decided between them.
 */
internal data class AlbumMatch<T>(
    val candidate: T,
    val tier: TitleMatcher.TitleTier,
    val artistQuality: Int,
    val tieBreaks: AlbumEvidence,
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
    vararg tieBreaks: AlbumTieBreak<T>,
): AlbumMatch<T>? {
    val matches = filter { ArtistMatcher.isMatch(requestedArtist, artistNameOf(it)) }
        .map { candidate ->
            AlbumMatch(
                candidate = candidate,
                tier = titleTierOf(candidate),
                artistQuality = ArtistMatcher.matchQuality(requestedArtist, artistNameOf(candidate)),
                tieBreaks = AlbumEvidence.of(tieBreaks.map { TieBreakEvidence(it.name, it.predicate(candidate)) }),
            )
        }
        .filter { it.tier != TitleMatcher.TitleTier.NONE }

    var comparator = compareBy<AlbumMatch<T>> { it.tier }.thenBy { it.artistQuality }
    for (tieBreak in tieBreaks) {
        comparator = comparator.thenBy { it.tieBreaks[tieBreak.name] }
    }
    return matches.maxWithOrNull(comparator)
        ?.also { ProbeTrace.picked("albumSelection", artistNameOf(it.candidate), it.candidate.toString()) }
}
