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
     * Neither the name nor any alias matched; the candidate was picked on some other signal
     * (the upstream's own relevance ranking — tags, disambiguation text). The weakest factor of
     * any tier, below [ALIAS]: no name agreement is the weakest identification claim this ranker
     * can make, and its reported confidence must not exceed a genuine alias match's. Still above
     * the engine's 0.5 `minConfidence` floor, so a full-score upstream hit is reported, not dropped.
     */
    NONE(0.7f),
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

/**
 * [NameMatchTier.CANONICAL] when [ownNames] — other names the *answering* source publishes for the
 * candidate itself — hold [requested]; this tier unchanged otherwise, null included.
 *
 * An alias-pool tier is a second source's claim that two names are one entity. A candidate whose own
 * record carries the requested name needs no such claim: the source that answered says the entity is
 * named as the request asked, and which of its fields carries that name is its cataloguing
 * convention, not a weaker identification. Absence corroborates nothing either way — such a list is
 * what contributors happened to file, so a name missing from it is not evidence of a different act,
 * and the pool's own tier stands.
 *
 * Same-name only, as an alias-pool match is: containment across a name list multiplies the chances
 * of a partial match.
 */
internal fun NameMatchTier?.corroboratedBy(requested: String, ownNames: List<String>): NameMatchTier? =
    if (ownNames.any { sameName(requested, it) }) NameMatchTier.CANONICAL else this

/**
 * [ArtistMatcher.matchQuality]'s same-name rank. The empty-normalization case — a name written
 * entirely in another script, or one whose only Latin content was a stripped "the " prefix — is
 * [ArtistMatcher]'s rule to own; this delegates rather than keeping a second copy of it.
 */
private fun sameName(expected: String, candidate: String): Boolean =
    ArtistMatcher.matchQuality(expected, candidate) == ArtistMatcher.QUALITY_SAME_NAME
