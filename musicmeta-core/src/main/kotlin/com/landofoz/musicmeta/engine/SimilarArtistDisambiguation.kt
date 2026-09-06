package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.isMusicBrainzIdShape

/**
 * A similar-artist name as the merge keys it: trimmed and lowercased.
 *
 * Shared with [SimilarArtistMerger.groupArtists] rather than copied, because the two must agree
 * exactly. A second normalization here would make this file ask MusicBrainz about entries the merge
 * never split, and miss the ones it did — `docs/pitfalls.md` §32.
 */
internal fun similarArtistNameKey(name: String): String = name.trim().lowercase()

/** A MusicBrainz id as the merge keys it, or null for a blank one — which is no id at all. */
internal fun similarArtistMbidKey(mbid: String?): String? =
    mbid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * Which merged similar artists a MusicBrainz lookup would describe, and how its answer is applied.
 *
 * Pure, and deliberately so: the decision about which entries are worth a request is the expensive
 * part of this feature and the part most likely to be got wrong, so it is testable without a
 * transport, a coroutine or an engine. `DefaultEnrichmentEngine` holds only the wiring around it.
 */
internal object SimilarArtistDisambiguation {

    /**
     * The MusicBrainz ids one batched lookup would answer for: an entry that carries an id, is still
     * undescribed, and shares its name key with at least one *other* entry of [artists].
     *
     * **The same-name condition is the cost.** Two merged entries under one name is the case a
     * consumer cannot read — everything else in the list is already told apart by its name. Asking
     * about every undescribed id instead would put a hundred-row Labs answer into the query; asking
     * only about split pairs cost 2 requests across the twelve-artist workload it was measured on,
     * and 1 in the worst single call.
     *
     * Capped at [BATCH_LIMIT]. Ids beyond it are dropped rather than paged: this is a label, and a
     * second round trip on a 1 req/s limiter is not worth one.
     */
    internal fun undescribedSplitPairMbids(artists: List<SimilarArtist>): List<String> {
        val sharedNames = artists
            .groupingBy { similarArtistNameKey(it.name) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        return artists
            .asSequence()
            .filter { it.disambiguation.isNullOrBlank() }
            .filter { similarArtistNameKey(it.name) in sharedNames }
            .mapNotNull { similarArtistMbidKey(it.identifiers.musicBrainzId) }
            // Shape-checked before the cap, not after: these ids come off other providers' answers,
            // and `MusicBrainzApi` drops anything that is not a UUID when it builds the query. A cap
            // counted first would spend its 25 places on ids the query then throws away.
            .filter { it.isMusicBrainzIdShape() }
            .distinct()
            .take(BATCH_LIMIT)
            .toList()
    }

    /**
     * [artists], with each undescribed entry labelled from [texts] under **its own** id.
     *
     * The join is on the id and nothing else. Joining on the name is the defect this whole feature
     * exists to avoid: the two entries it would be joining are, by construction, the two that share
     * a name, so a name join hands each of them the other act's description as readily as its own.
     *
     * An id [texts] does not carry stays `null` — MusicBrainz answers a retired or merged id with
     * nothing, and a missing answer is never filled from another row of the same response.
     */
    internal fun describedWith(
        artists: List<SimilarArtist>,
        texts: Map<String, String>,
    ): List<SimilarArtist> {
        if (texts.isEmpty()) return artists
        return artists.map { artist ->
            if (!artist.disambiguation.isNullOrBlank()) return@map artist
            val text = similarArtistMbidKey(artist.identifiers.musicBrainzId)?.let { texts[it] }
            if (text.isNullOrBlank()) artist else artist.copy(disambiguation = text)
        }
    }

    /** How many ids one batched lookup may name. */
    internal const val BATCH_LIMIT = 25
}
