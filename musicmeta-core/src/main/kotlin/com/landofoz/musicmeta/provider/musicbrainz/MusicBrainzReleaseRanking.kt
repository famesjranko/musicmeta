package com.landofoz.musicmeta.provider.musicbrainz

/**
 * Tie-break policy for [MusicBrainzEnricher.resolveAlbumSearch]'s release pool. A bare album title
 * routinely ties dozens of releases at MusicBrainz's maximum search score — "Master Of Puppets" ties
 * the top 25 of 76 hits at score 100 — so taking the first hit at or above the floor yields whichever
 * release MB's own, undocumented tie order happened to return: live bootlegs, promo pressings, box
 * sets and same-titled singles all win that way.
 *
 * [pickBestRelease] ranks identity (is this even the right KIND of record?) ABOVE MusicBrainz's own
 * score, unlike a naive "score first" ladder: "Purple Rain"/Prince's only score-100 release is a live
 * Bootleg while every genuine Official pressing scores 97 (`docs/pitfalls.md` §7 — score is not proof
 * of identity).
 */
internal object MusicBrainzReleaseRanking {

    /**
     * The best release at or above [minMatchScore], or null if none qualifies. Ranks tied candidates
     * by, earlier tiers dominating:
     * 1. is an Album
     * 2. carries no secondary types (not Live/Compilation/Remix/Soundtrack/…)
     * 3. status is Official (not Bootleg/Promotion/Pseudo-Release/…)
     * 4. MusicBrainz's own search [MusicBrainzRelease.score]
     * 5. inside the pool's edition band ([inBand]) — neither a partial pressing nor a box set
     * 6. evidence for [removedTags], the qualifier the caller asked for and the search had to strip
     * 7. earliest parseable release year; a release with no parseable year sorts last
     * 8. blank [pressingDisambiguation] — a plain pressing, album identity subtracted
     * 9. lowest id — a deterministic final backstop
     *
     * [removedTags] is empty for an ordinary title, which makes tier 6 inert. When a caller asked for
     * `"… (Remastered)"` and the qualifier had to be stripped to find anything, it carries that intent:
     * it sits ABOVE the date because the caller wants the remaster, not the earliest pressing, and
     * BELOW the band because a 137-track box set whose text says "remastered deluxe version" is not
     * what they asked for either. It sits below identity because tags match free text — a bootleg's
     * disambiguation can claim "remastered" as readily as an official pressing's.
     *
     * Tiers 1-3 rank rather than filter, which is what lets an album that genuinely IS live
     * ("MTV Unplugged in New York", where every candidate carries `+Live`) still resolve: a pool with
     * no plain-Album candidate scores every candidate alike on that tier, so the tier self-neutralises
     * and the next one decides. Filtering on these instead would empty such a pool.
     */
    fun pickBestRelease(
        candidates: List<MusicBrainzRelease>,
        minMatchScore: Int,
        removedTags: List<MusicBrainzQualifierFallback.QualifierTag> = emptyList(),
    ): MusicBrainzRelease? {
        val eligible = candidates.filter { it.score >= minMatchScore }
        if (eligible.isEmpty()) return null
        val modal = modalTrackCount(eligible)
        val comparator = compareBy<MusicBrainzRelease> { it.releaseType == "Album" }
            .thenBy { it.secondaryTypes.isEmpty() }
            .thenBy { it.status == "Official" }
            .thenBy { it.score }
            .thenBy { inBand(it, modal) }
            .thenBy { MusicBrainzQualifierFallback.tagEvidence(qualifierText(it), removedTags) }
            // Earliest year wins: a missing year is mapped to Int.MAX_VALUE so it always loses to
            // any parsed year once the descending order flips actual years to prefer the smallest.
            .thenByDescending { leadingYear(it.date) ?: Int.MAX_VALUE }
            .thenBy { pressingDisambiguation(it).isBlank() }
            .thenByDescending { it.id }
        return eligible.maxWithOrNull(comparator)
    }

    /**
     * The text a stripped qualifier is matched against: the release's own disambiguation and title.
     * The raw [MusicBrainzRelease.disambiguation] is deliberate here, not [pressingDisambiguation] —
     * the qualifier the caller stripped describes a pressing, and subtracting the album's name would
     * not remove any of the edition wording it looks for.
     */
    private fun qualifierText(release: MusicBrainzRelease): String =
        "${release.disambiguation.orEmpty()} ${release.title}"

    /**
     * Neither a partial pressing nor a box set relative to [modal] — 0.8x..1.25x inclusive. A null
     * [MusicBrainzRelease.trackCount] or null [modal] always counts as inside the band.
     *
     * Deliberately the opposite default to the date tier, where a missing date sorts last: a search
     * hit that omits `track-count` is far more often an ordinary pressing MB has not fully entered
     * than an unlisted box set, so treating the absence as out-of-band would discard real pressings.
     */
    private fun inBand(release: MusicBrainzRelease, modal: Int?): Boolean {
        val count = release.trackCount ?: return true
        val centre = modal ?: return true
        return count * 5 >= centre * 4 && count * 4 <= centre * 5
    }

    /** The leading 4-digit year of a MusicBrainz date string (`"1986-03-03"` -> `1986`), or null. */
    private fun leadingYear(date: String?): Int? {
        val text = date ?: return null
        if (text.length < YEAR_LENGTH) return null
        val prefix = text.substring(0, YEAR_LENGTH)
        return prefix.toIntOrNull()
    }

    /**
     * [MusicBrainzRelease.disambiguation] with its release group's [MusicBrainzRelease.releaseGroupDisambiguation]
     * subtracted (case-insensitively), then trimmed of surrounding whitespace and `,;:-`.
     *
     * MusicBrainz echoes the release-group's disambiguation onto the release: six albums are titled
     * "Weezer" and the group carries "Blue Album" while the release carries "blue album" or "Red
     * Album, deluxe". Reading the raw field makes "which album is this" look like "this is a variant
     * pressing" — subtracting the group's own text leaves only the pressing-specific remainder.
     */
    fun pressingDisambiguation(release: MusicBrainzRelease): String {
        var text = release.disambiguation.orEmpty()
        val groupText = release.releaseGroupDisambiguation
        if (!groupText.isNullOrEmpty()) {
            text = text.replace(groupText, "", ignoreCase = true)
        }
        return text.trim { it in TRIM_CHARS }
    }

    /**
     * The most common non-null [MusicBrainzRelease.trackCount] among [candidates], ties broken to the
     * lower count. Null if no candidate carries a track count. Mode, not median: a pool is many
     * pressings of one record, and the median drifts with however many box sets are in the window.
     * Does not filter by score — callers that want a score floor apply it before calling.
     */
    fun modalTrackCount(candidates: List<MusicBrainzRelease>): Int? {
        val counts = candidates.mapNotNull { it.trackCount }
        if (counts.isEmpty()) return null
        val byCount = counts.groupingBy { it }.eachCount()
        return byCount.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .first().key
    }

    private const val YEAR_LENGTH = 4
    private val TRIM_CHARS = charArrayOf(' ', ',', ';', ':', '-')
}
