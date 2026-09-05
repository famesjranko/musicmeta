package com.landofoz.musicmeta.engine

/**
 * A candidate a name-search provider accepted, and how its name matched — the tier is what scales
 * the confidence that candidate is reported with.
 */
internal data class ArtistNameMatch<T>(val value: T, val tier: NameMatchTier)

/**
 * How [candidate] matched [requested], consulting this call's alias pool only when [requested]
 * itself does not match — or null when no known name form matches it at all.
 *
 * [NameMatchTier.CANONICAL] means the requested name matched on its own terms, at any of
 * [ArtistMatcher]'s quality ranks, and carries factor 1.0: a request whose provider already answers
 * under the name it asked with reports exactly the confidence it reported before a pool existed.
 * A pool match is granted at same-name only — a pool multiplies the chances of a partial match, so
 * "Radiohead" must not reach an alias of "Radiohead & Thom Yorke" by containment — and reports
 * [NameMatchTier.PRIMARY_ALIAS] or [NameMatchTier.ALIAS] on the source's own official flag.
 *
 * The pool is read *after* the requested name fails, which is what keeps the lookup some pools cost
 * off every request that never needed one.
 */
internal suspend fun artistNameTier(
    requested: String,
    candidate: String,
    minTokenOverlap: Float = ArtistMatcher.DEFAULT_MIN_TOKEN_OVERLAP,
): NameMatchTier? {
    if (ArtistMatcher.isMatch(requested, candidate, minTokenOverlap)) return NameMatchTier.CANONICAL
    return aliasTier(candidate, resolvedAliasPool())
}

/** [artistNameTier] against a pool already read this call, for a caller ranking a whole search pool. */
internal fun artistNameTier(
    requested: String,
    candidate: String,
    aliases: List<AlternativeName>,
    minTokenOverlap: Float = ArtistMatcher.DEFAULT_MIN_TOKEN_OVERLAP,
): NameMatchTier? {
    if (ArtistMatcher.isMatch(requested, candidate, minTokenOverlap)) return NameMatchTier.CANONICAL
    return aliasTier(candidate, aliases)
}

/**
 * [artistNameTier] with the requested name accepted at same name only.
 *
 * For a caller comparing against a string it derived by taking a candidate's field apart. Splitting
 * multiplies the strings one request is compared against exactly as an alias pool does, so the
 * looser ranks [ArtistMatcher] accepts are refused here for the reason [nameMatchTier] refuses them
 * of an alias: a partial match against one of many strings is not a name.
 */
internal fun sameNameTier(
    requested: String,
    candidate: String,
    aliases: List<AlternativeName>,
): NameMatchTier? {
    if (ArtistMatcher.matchQuality(requested, candidate) == ArtistMatcher.QUALITY_SAME_NAME) {
        return NameMatchTier.CANONICAL
    }
    return aliasTier(candidate, aliases)
}

private fun aliasTier(candidate: String, aliases: List<AlternativeName>): NameMatchTier? {
    val matched = aliases.filter {
        ArtistMatcher.matchQuality(it.name, candidate) == ArtistMatcher.QUALITY_SAME_NAME
    }
    return when {
        matched.isEmpty() -> null
        matched.any { it.official } -> NameMatchTier.PRIMARY_ALIAS
        else -> NameMatchTier.ALIAS
    }
}

/**
 * [bestArtistMatch], retried against this call's alias pool when nothing matched [expected] —
 * the same selection rule, applied to a second set of names for the same entity.
 *
 * The retry ranks alias matches by tier (an official alias beats a search hint) and then by
 * [tieBreak], because every alias match is the same [ArtistMatcher] quality: same-name.
 */
internal suspend fun <T> Iterable<T>.bestArtistMatchOrAlias(
    expected: String,
    tieBreak: Comparator<T>? = null,
    nameOf: (T) -> String,
): ArtistNameMatch<T>? {
    bestArtistMatch(expected, tieBreak, nameOf)?.let { return ArtistNameMatch(it, NameMatchTier.CANONICAL) }
    val aliases = resolvedAliasPool()
    if (aliases.isEmpty()) return null
    val byTier = compareBy<Pair<T, NameMatchTier>> { it.second.confidenceFactor }
    val comparator = if (tieBreak == null) byTier else byTier.then(compareBy(tieBreak) { it.first })
    return mapNotNull { candidate ->
        aliasTier(nameOf(candidate), aliases)?.let { candidate to it }
    }.maxWithOrNull(comparator)?.let { ArtistNameMatch(it.first, it.second) }
}

/**
 * [acceptAndRankAlbum], retried against this call's alias pool when the requested artist name
 * accepted nothing — the title policy is untouched, only the artist floor widens.
 *
 * Official aliases are tried before the rest, so a candidate under a name the entity is published
 * under beats one under a search hint whatever order the source listed them in.
 */
internal suspend fun <T> Iterable<T>.acceptAndRankAlbumOrAlias(
    requestedArtist: String,
    artistNameOf: (T) -> String,
    titleTierOf: (T) -> TitleMatcher.TitleTier,
    vararg tieBreaks: AlbumTieBreak<T>,
): AlbumMatch<T>? {
    acceptAndRankAlbum(requestedArtist, artistNameOf, titleTierOf, *tieBreaks)?.let { return it }
    val aliases = resolvedAliasPool()
    if (aliases.isEmpty()) return null
    return aliases.sortedByDescending { it.official }.firstNotNullOfOrNull { alias ->
        filter { ArtistMatcher.matchQuality(alias.name, artistNameOf(it)) == ArtistMatcher.QUALITY_SAME_NAME }
            .acceptAndRankAlbum(alias.name, artistNameOf, titleTierOf, *tieBreaks)
            ?.let { match ->
                // The winner's own name against the whole pool, not the alias that reached it: a
                // candidate matching an official alias listed later is still a primary-alias match.
                match.copy(nameTier = aliasTier(artistNameOf(match.candidate), aliases) ?: NameMatchTier.ALIAS)
            }
    }
}
