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
