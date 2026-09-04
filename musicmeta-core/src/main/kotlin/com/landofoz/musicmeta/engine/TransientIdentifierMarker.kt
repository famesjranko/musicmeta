package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.IdentifierRequirement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-[DefaultEnrichmentEngine.enrich] scoped record of which [IdentifierRequirement]s a transient
 * left unresolved this run — installed once per call, alongside `EnrichDeadline`
 * (`http/DefaultHttpClient.kt`), and read back by [ProviderChain.skippedIdentifierRequirements]'s
 * caller in `DefaultEnrichmentEngine.resolveTypes` to tell "a provider was skipped because a
 * prerequisite identifier lookup hiccupped this run" from "the identifier genuinely doesn't exist."
 *
 * A [ConcurrentHashMap]-backed set, not a plain `MutableSet` or a `Boolean`: [mark] can be called
 * from `DefaultEnrichmentEngine.resolveIdentity`'s catch and from the catch blocks in
 * `MusicBrainzArtistResolution` and `MusicBrainzAlbumResolution`, all of which may run inside
 * concurrently-launched `async` children of the same `enrich()` call.
 */
internal class TransientIdentifierMarker : AbstractCoroutineContextElement(Key) {
    private val unresolved = java.util.concurrent.ConcurrentHashMap.newKeySet<IdentifierRequirement>()

    /** Records that [requirements] came back unresolved this run because of a transient, not an absence. */
    fun mark(vararg requirements: IdentifierRequirement) {
        unresolved.addAll(requirements)
    }

    /**
     * A failed identity-resolution call could have supplied any of the four concrete identifiers —
     * used by [DefaultEnrichmentEngine.resolveIdentity]'s catch, which doesn't know which one(s) it
     * would have found.
     */
    fun markAllConcreteIdentifiers() = mark(
        IdentifierRequirement.MUSICBRAINZ_ID,
        IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID,
        IdentifierRequirement.WIKIDATA_ID,
        IdentifierRequirement.WIKIPEDIA_TITLE,
    )

    /**
     * True when [skipped] — the requirements that caused a provider in some chain to be
     * skipped — overlaps what a transient left unresolved this run. [IdentifierRequirement.ANY_IDENTIFIER]
     * is satisfied by any one of the four concrete identifiers, so a skip on it counts as a match
     * whenever *anything* was left unresolved, even though nothing ever marks the literal
     * `ANY_IDENTIFIER` value.
     */
    fun matches(skipped: Set<IdentifierRequirement>): Boolean =
        skipped.any { it in unresolved } ||
            (IdentifierRequirement.ANY_IDENTIFIER in skipped && unresolved.isNotEmpty())

    internal companion object Key : CoroutineContext.Key<TransientIdentifierMarker>
}
