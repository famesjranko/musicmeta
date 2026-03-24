package com.landofoz.musicmeta

/**
 * Top-level identity resolution outcome for an enrichment request.
 *
 * This is set by the engine after MusicBrainz identity resolution and captures
 * the result once, rather than requiring consumers to scan individual results.
 *
 * `null` on [EnrichmentResults.identity] means identity resolution was not
 * attempted (MBID was pre-provided, all types cached, or resolution disabled).
 */
data class IdentityResolution(
    /** Resolved identifiers (MBIDs, Wikidata, Wikipedia). */
    val identifiers: EnrichmentIdentifiers,
    /** How the identity resolution went. */
    val match: IdentityMatch?,
    /** Match score (0-100), same scale as [SearchCandidate.score]. Only set when [match] is [IdentityMatch.RESOLVED]. */
    val matchScore: Int?,
    /** Near-miss candidates when [match] is [IdentityMatch.SUGGESTIONS]. */
    val suggestions: List<SearchCandidate> = emptyList(),
)
