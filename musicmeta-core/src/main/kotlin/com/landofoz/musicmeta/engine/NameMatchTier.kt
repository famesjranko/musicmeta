package com.landofoz.musicmeta.engine

/**
 * An alternative name an upstream holds for an entity, reduced to the two things ranking needs.
 *
 * Source-agnostic on purpose: MusicBrainz aliases fill it today, and a second upstream that also
 * publishes alternative names (Wikidata's `aliases`) can fill the same shape without a second
 * matching rule growing beside this one. What "official" means is the *source's* judgement, so each
 * one maps its own fields onto [official] rather than this file learning their vocabularies.
 */
internal data class AlternativeName(
    val name: String,
    /** The source marks this as a name the entity genuinely goes by, not a search aid or misspelling. */
    val official: Boolean,
)

/**
 * How a requested name matched an entity — the tier, not a boolean, because a pseudonym hit and a
 * canonical-name hit are different claims and a consumer reading `identityMatchScore` has to be able
 * to tell them apart.
 *
 * [confidenceFactor] scales the identification confidence a caller already computed. It scales
 * rather than replaces so a weak upstream relevance score cannot be *raised* by matching an alias,
 * and every tier stays above the engine's 0.5 `minConfidence` floor for a full-score hit.
 */
internal enum class NameMatchTier(val confidenceFactor: Float) {
    /** The entity's own name. */
    CANONICAL(1.0f),

    /** An [AlternativeName.official] alias — a localised or primary name the entity is published under. */
    PRIMARY_ALIAS(0.95f),

    /** Any other alias: a search hint, a misspelling, a pseudonym. */
    ALIAS(0.85f),

    /**
     * Neither the name nor any alias matched; the candidate was picked on some other signal. Its
     * factor is 1.0 rather than a penalty: such a candidate is ranked and scored exactly as it was
     * before aliases were read, and only the two alias tiers are a decided change to confidence.
     */
    NONE(1.0f),
}

/**
 * The best tier at which [requested] matches [canonical] or one of [aliases].
 *
 * Equality is [ArtistMatcher]'s normalized same-name test, not string equality — the alias surface
 * exists for names that differ in script, diacritics and punctuation, so a comparison that fails on
 * those would reject exactly the cases it was added for. A looser tier (containment, token overlap)
 * is deliberately not accepted: an alias pool multiplies the chances of a partial match, and
 * "Radiohead" must not match an alias of "Radiohead & Thom Yorke" as though it were a name.
 */
internal fun nameMatchTier(
    requested: String,
    canonical: String,
    aliases: List<AlternativeName>,
): NameMatchTier {
    if (sameName(requested, canonical)) return NameMatchTier.CANONICAL
    val matched = aliases.filter { sameName(requested, it.name) }
    if (matched.isEmpty()) return NameMatchTier.NONE
    return if (matched.any { it.official }) NameMatchTier.PRIMARY_ALIAS else NameMatchTier.ALIAS
}

private val LATIN_ALPHANUMERIC = Regex("[a-z0-9]")

/**
 * [ArtistMatcher.matchQuality]'s same-name rank, except for a name it cannot see: its normalization
 * keeps `[a-z0-9 ]` only, so a name written entirely in another script normalizes to the empty
 * string — and two empty strings compare equal. Localised aliases are precisely such names
 * (`コールドプレイ`, `电台司令`), so without this the alias surface would match every non-Latin name
 * to every other. Those compare raw instead, case-folded and trimmed.
 */
private fun sameName(expected: String, candidate: String): Boolean {
    val comparable = LATIN_ALPHANUMERIC.containsMatchIn(expected.lowercase()) &&
        LATIN_ALPHANUMERIC.containsMatchIn(candidate.lowercase())
    if (!comparable) return expected.trim().equals(candidate.trim(), ignoreCase = true)
    return ArtistMatcher.matchQuality(expected, candidate) == ArtistMatcher.QUALITY_SAME_NAME
}
