package com.landofoz.musicmeta

/**
 * Top-level identity resolution outcome for an enrichment request.
 *
 * This is set by the engine after MusicBrainz identity resolution and captures
 * the result once, rather than requiring consumers to scan individual results.
 *
 * `null` on [EnrichmentResults.identity] means identity resolution was not
 * attempted (MBID was pre-provided, all types cached, or resolution disabled).
 * A resolution that was attempted but failed is never `null` — it carries
 * [IdentityMatch.UNVERIFIED].
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
    /**
     * Canonical title of the resolved entity — an artist's name on an artist request, as on
     * [SearchCandidate.title]. `null` when resolution named no entity: a request that resolved by
     * search leaves it unset, because a search hit is what a *name* matched, not what an identifier
     * named. Never overwritten onto the request — see the request's own fields for what the
     * providers were asked with.
     */
    val title: String? = null,
    /**
     * Canonical artist credit of the resolved entity, joined as MusicBrainz joins it ("Queen &
     * David Bowie"). `null` on an artist request, whose name is [title], and `null` whenever
     * resolution named no entity.
     */
    val artist: String? = null,
)
