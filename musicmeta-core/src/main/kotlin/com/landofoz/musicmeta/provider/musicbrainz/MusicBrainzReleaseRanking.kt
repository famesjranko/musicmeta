package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.ArtistMatcher

/**
 * Tie-break policy for a MusicBrainz release search pool, where a bare album title routinely ties
 * dozens of releases at the maximum score. Identity ranks above MusicBrainz's own score, since
 * score is not proof of identity (`docs/pitfalls.md` §7).
 */
internal object MusicBrainzReleaseRanking {

    /**
     * The best release at or above [minMatchScore], or null if none qualifies. Ranks tied candidates
     * by, earlier tiers dominating:
     * 0. [ArtistMatcher.matchQuality] against [artist], the best of any of
     *    [MusicBrainzRelease.artistCredits]' individually credited names — leads every other tier,
     *    so a release credited to someone else never outranks one credited to the requested artist
     *    on edition signals alone. Ranks rather than rejects: a pool with no matching-artist
     *    candidate at all still resolves to its best edition match, tied at
     *    [ArtistMatcher.QUALITY_NONE]. A null or blank [artist] (the two callers that already
     *    filter their pool with `anyArtistMatches` before ranking pass none) leaves this tier inert.
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
     * [removedTags] is empty for an ordinary title, which makes tier 6 inert. Tiers 1-3 rank rather
     * than filter, so a pool whose every candidate is live still resolves instead of emptying.
     */
    fun pickBestRelease(
        candidates: List<MusicBrainzRelease>,
        minMatchScore: Int,
        removedTags: List<MusicBrainzQualifierFallback.QualifierTag> = emptyList(),
        artist: String? = null,
    ): MusicBrainzRelease? {
        val eligible = candidates.filter { it.score >= minMatchScore }
        if (eligible.isEmpty()) return null
        val modal = modalTrackCount(eligible)
        val comparator = compareBy<MusicBrainzRelease> { artistQuality(it, artist) }
            .thenBy { it.releaseType == "Album" }
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

    private fun artistQuality(release: MusicBrainzRelease, artist: String?): Int =
        if (artist.isNullOrBlank()) {
            ArtistMatcher.QUALITY_NONE
        } else {
            release.artistCredits.maxOfOrNull { ArtistMatcher.matchQuality(artist, it) } ?: ArtistMatcher.QUALITY_NONE
        }

    /**
     * The text a stripped qualifier is matched against. Deliberately the raw
     * [MusicBrainzRelease.disambiguation], not [pressingDisambiguation]: the stripped qualifier
     * describes a pressing, so the edition wording must survive.
     */
    private fun qualifierText(release: MusicBrainzRelease): String =
        "${release.disambiguation.orEmpty()} ${release.title}"

    /**
     * Neither a partial pressing nor a box set relative to [modal] — 0.8x..1.25x inclusive. A null
     * [MusicBrainzRelease.trackCount] or null [modal] counts as inside, the opposite default to the
     * date tier: an omitted count usually means an under-entered pressing, not an unlisted box set.
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
     * MusicBrainz echoes the group's disambiguation onto the release, which makes "which album is
     * this" ("blue album") read as "this is a variant pressing". Subtracting it leaves the remainder.
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
     * lower count, or null if none carries one. Mode, not median: the median drifts with however many
     * box sets are in the window. Applies no score floor; callers apply it before calling.
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
