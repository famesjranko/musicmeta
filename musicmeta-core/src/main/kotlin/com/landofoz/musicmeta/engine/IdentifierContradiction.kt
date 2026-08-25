package com.landofoz.musicmeta.engine

/**
 * Whether the entity a caller's identifier names is *confidently not* the entity they described.
 *
 * A successful identifier lookup proves the identifier exists. It does not prove that it identifies
 * what the caller asked for, and nothing else in the engine ever compares the two: a request naming
 * `Radiohead` beside another artist's live MBID returns that artist's data, at full confidence,
 * under the strongest provenance there is.
 *
 * This answers one question and refuses the other. **Absence of contradiction is not corroboration** —
 * a `false` here means "not confidently different", never "confirmed the same", and no caller may
 * read it as agreement. Positive agreement is a separate test with its own evidence
 * ([nameMatchTier]), and neither is the negation of the other; the gap between them is *unknown*.
 *
 * Deliberately asymmetric, because the costs are asymmetric: wrongly rejecting a correct identifier
 * because two spellings of one artist are hard to equate destroys a request that was working, while
 * missing a contradiction leaves the behaviour that already exists. Every uncertain case is
 * therefore `false`.
 */
internal fun contradictsSuppliedName(
    supplied: String,
    canonical: String,
    aliases: List<AlternativeName>,
): Boolean {
    if (supplied.isBlank() || canonical.isBlank()) return false
    // Every name the upstream itself holds, search hints and misspellings included. Those are not
    // names an entity goes by ([AlternativeName.official] says which are), but matching one can only
    // ever *prevent* a contradiction, so including them is the conservative direction.
    val known = (listOf(canonical) + aliases.map { it.name }).filter { it.isNotBlank() }
    if (known.any { ArtistMatcher.matchQuality(supplied, it) > ArtistMatcher.QUALITY_NONE }) return false
    // A comparison that cannot represent equality cannot prove inequality either. `東京事変` and
    // `Tokyo Jihen` are one band; they share no character, and the aliases that relate them are not
    // guaranteed to be present. Refuse to judge unless some name upstream holds is written in a
    // script this can compare against.
    if (known.none { sharesScript(supplied, it) }) return false
    return true
}

/** Whether two names have any writing system in common, ignoring digits and punctuation. */
private fun sharesScript(left: String, right: String): Boolean =
    (scriptsOf(left) intersect scriptsOf(right)).isNotEmpty()

private fun scriptsOf(name: String): Set<Character.UnicodeScript> =
    name.filter { it.isLetter() }
        .map { Character.UnicodeScript.of(it.code) }
        .filterNot { it == Character.UnicodeScript.COMMON || it == Character.UnicodeScript.INHERITED }
        .toSet()
