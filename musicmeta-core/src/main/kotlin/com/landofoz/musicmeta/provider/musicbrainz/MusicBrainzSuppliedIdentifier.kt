package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.SuppliedIdentifierContradiction
import com.landofoz.musicmeta.engine.contradictsSuppliedName
import kotlinx.coroutines.currentCoroutineContext

/**
 * A caller-supplied MusicBrainz identifier, checked against the name the caller supplied beside it.
 *
 * A successful lookup proves the identifier names an entity. It does not prove that entity is the
 * one the request described, and nothing else in a response says otherwise — so every path that
 * resolves an entity from a caller's own identifier passes through here. Guarding one path says
 * nothing about the others: `BAND_MEMBERS`, `ARTIST_LINKS`, `ARTIST_POPULARITY`,
 * `ARTIST_DISCOGRAPHY`, `ALBUM_TRACKS`, `CREDITS` and `RELEASE_EDITIONS` each reach their entity by
 * a different route, and each went unguarded when only the first route had the check.
 * `SuppliedIdentifierGuardMatrixTest` is what holds the whole surface to it.
 *
 * The check is on the **artist**, never the title: a remaster, an edition or a localised title
 * differs from what a caller typed while still being the entity they meant, and a recording title
 * carries mix, live and remaster qualifiers a caller's tag will not.
 *
 * It reports only *confident disagreement*. A `false` means "not confidently different", never
 * "confirmed the same" — absence of contradiction is not corroboration (`docs/pitfalls.md` §25).
 */
internal suspend fun markIfDifferentArtist(supplied: String, credit: String): Boolean {
    if (!contradictsSuppliedName(supplied, credit, emptyList())) return false
    currentCoroutineContext()[SuppliedIdentifierContradiction]?.mark()
    return true
}

/**
 * This lookup unless it names a confidently different artist, in which case
 * [MusicBrainzLookup.Absent] — deliberately joining the case where MusicBrainz holds no such entity.
 *
 * Both mean the identifier cannot answer for the entity described, and both leave a request that
 * carries a name free to be answered from it: returning the requested entity beats returning
 * nothing. The contradiction is marked on the call before the [MusicBrainzLookup.Absent], so
 * recovering by name never hides the bad identifier.
 *
 * One generic function rather than an overload per entity: the two erase to the same JVM signature,
 * so the compiler rejects the pair outright.
 *
 * [creditOf] is the artist the entity is credited to — its own name for an artist, the release or
 * recording credit otherwise. [aliasesOf] defaults to none, which is right for a release or
 * recording: MusicBrainz files aliases on the artist entity, and neither response carries them.
 */
internal suspend fun <T> MusicBrainzLookup<T>.unlessDifferentArtist(
    requested: String,
    creditOf: (T) -> String,
    aliasesOf: (T) -> List<AlternativeName> = { emptyList() },
): MusicBrainzLookup<T> {
    val found = this as? MusicBrainzLookup.Found ?: return this
    if (!contradictsSuppliedName(requested, creditOf(found.value), aliasesOf(found.value))) return this
    currentCoroutineContext()[SuppliedIdentifierContradiction]?.mark()
    return MusicBrainzLookup.Absent
}

/** MusicBrainz's alias `type` for a typo-catcher, which is not a name the artist goes by. */
private const val SEARCH_HINT_ALIAS_TYPE = "Search hint"

/**
 * This artist's aliases as [AlternativeName]s.
 *
 * `official` is false for a search hint: MusicBrainz files typo-catchers ("Coolplay") in the same
 * array as localised names ("コールドプレイ"), and only the second kind is a name the artist goes by.
 */
internal fun MusicBrainzArtist.alternativeNames(): List<AlternativeName> =
    aliases.map {
        val isSearchHint = it.type.equals(SEARCH_HINT_ALIAS_TYPE, ignoreCase = true)
        AlternativeName(name = it.name, official = !isSearchHint && (it.primary || it.locale != null))
    }

/**
 * This lookup unless the caller dates their album *before* the release group's own first release,
 * in which case [MusicBrainzLookup.Absent] on the same terms as [unlessDifferentArtist].
 *
 * The artist check cannot see a different album by the *same* artist, and a title comparison is the
 * mess it was written to avoid (`docs/pitfalls.md` §7). This is the structured evidence instead: an
 * album cannot predate its own first release, so a caller's earlier year is positive evidence of a
 * different album. It costs no request — `inc=release-groups` already carries the date.
 *
 * **One-sided on purpose.** A caller's *later* year is any reissue, remaster or region pressing and
 * is not judgeable, so nothing is reported there. That gives up roughly half the catch rate before
 * a line is written, which is the trade the measurement asked for: a false contradiction tells a
 * caller their correct identifier is wrong and nothing else in the response disagrees.
 *
 * [YEAR_SLACK] absorbs region and calendar-boundary sloppiness in the caller's tag and in
 * MusicBrainz's own partial dates (`"1997"`, `"1997-05"`) alike.
 *
 * Measured 2026-08-25 on 181 studio release groups and 3139 live releases: **0 false positives**,
 * against a track-count rule frozen beside it that scored 31729 out of 109604 (29%) and was
 * rejected on its own numbers. `AlbumYearContradictionCorpusTest` re-runs both every build.
 *
 * That zero supports one population — a **correct** identifier beside caller metadata from another
 * legitimate pressing of the same album — and is not evidence that comparing years is universally
 * safe. The caller whose own tag predates MusicBrainz's first release is outside the corpus, since
 * every year in it comes from MusicBrainz. `corpora/album-year-contradiction/provenance.md` and
 * `.scratch/album-mbid-contradiction/spec.md` hold the rest, including one probe that turned out
 * vacuous.
 */
internal suspend fun MusicBrainzLookup<MusicBrainzRelease>.unlessPredatingFirstRelease(
    callerYear: Int?,
): MusicBrainzLookup<MusicBrainzRelease> {
    val found = this as? MusicBrainzLookup.Found ?: return this
    val year = callerYear ?: return this
    val firstReleased = yearOf(found.value.releaseGroupFirstReleaseDate) ?: return this
    if (year >= firstReleased - YEAR_SLACK) return this
    currentCoroutineContext()[SuppliedIdentifierContradiction]?.mark()
    return MusicBrainzLookup.Absent
}

/** Years of leeway before a caller's earlier year counts as disagreement rather than sloppiness. */
private const val YEAR_SLACK = 1

/** The year in a MusicBrainz date, which may be `"1997"`, `"1997-05"` or `"1997-05-21"`. */
private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()
