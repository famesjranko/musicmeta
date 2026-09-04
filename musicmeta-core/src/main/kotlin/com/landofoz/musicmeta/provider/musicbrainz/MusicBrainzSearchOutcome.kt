package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ResolvedEntityNames
import com.landofoz.musicmeta.engine.TitleMatcher
import kotlinx.coroutines.currentCoroutineContext

/** How many near-misses a [notFoundWithSuggestions] answer offers. */
internal const val MAX_SUGGESTIONS = 3

/**
 * The shared NotFound cascade: an empty strict [pool] asks [fuzzy] for near-misses; a
 * non-empty pool that still failed to rank is suggested as-is.
 *
 * One of the three answers artist, album and track resolution give alike, beside the route a hit
 * self-reports ([searchProvenance]) and the ladder a stripped title is searched down
 * ([resolveViaQualifierFallback]).
 */
internal suspend fun <T> notFoundWithSuggestions(
    type: EnrichmentType,
    providerId: String,
    pool: List<T>,
    fuzzy: suspend () -> List<T>,
    toCandidate: (T) -> SearchCandidate,
): EnrichmentResult.NotFound = if (pool.isEmpty()) {
    EnrichmentResult.NotFound(type, providerId,
        suggestions = fuzzy().takeIf { it.isNotEmpty() }?.take(MAX_SUGGESTIONS)?.map(toCandidate))
} else {
    EnrichmentResult.NotFound(type, providerId,
        suggestions = pool.take(MAX_SUGGESTIONS).map(toCandidate))
}

/**
 * Which name route a search hit reached. A Lucene score measures relevance, not identity — a
 * truncated title comes back as a full-phrase match at 100 — so only [TitleMatcher.equivalent]
 * can say the returned title is the requested one. A qualifier fallback already knows it
 * searched something other than the caller's literal title and keeps its own route.
 */
internal fun searchProvenance(
    viaQualifierFallback: Boolean,
    requestedTitle: String,
    hitTitle: String?,
): LookupProvenance? = when {
    viaQualifierFallback -> LookupProvenance.QUALIFIER_FALLBACK_NAME
    hitTitle == null -> null
    TitleMatcher.equivalent(requestedTitle, hitTitle) -> LookupProvenance.EXACT_NAME
    else -> LookupProvenance.FUZZY_NAME
}

/**
 * Hands the engine the names of the entity a caller's **identifier** named, for the request
 * fields a caller holding only that identifier left blank ([ResolvedEntityNames]). The artist is
 * MusicBrainz's `artist-credit` joined with its own join phrases — "Queen & David Bowie", not
 * "Queen" — which is the string a name-search provider is asked with.
 *
 * Offered from the three lookup paths and never from a search. A search hit is what a *name*
 * resolved to, so backfilling from one would rewrite the request with whatever an under-specified
 * query happened to rank first — for "Under Pressure" with no artist, a Joss Stone cover. The
 * caller's own names are the better answer in that case, and they are already on the request.
 */
internal suspend fun offerNames(title: String?, artist: String?) {
    currentCoroutineContext()[ResolvedEntityNames]?.offer(title, artist)
}

/**
 * Tries each of [MusicBrainzQualifierFallback]'s fallback candidates (dropping the original
 * title — the caller has already searched it) in most-specific-first order, stopping at the
 * first one [resolve] resolves. Shared shape for
 * `MusicBrainzAlbumEnrichment.resolveAlbumQualifierFallback` and
 * `MusicBrainzTrackEnrichment.resolveTrackQualifierFallback`, which differ only in how a candidate
 * resolves.
 */
internal suspend fun <T> resolveViaQualifierFallback(
    title: String,
    resolve: suspend (MusicBrainzQualifierFallback.FallbackCandidate) -> T?,
): T? {
    for (candidate in MusicBrainzQualifierFallback.qualifierFallbackCandidates(title).drop(1)) {
        resolve(candidate)?.let { return it }
    }
    return null
}

internal fun anyArtistMatches(credits: List<String>, expectedNorm: String): Boolean =
    credits.any { MusicBrainzQualifierFallback.normalize(it) == expectedNorm }
