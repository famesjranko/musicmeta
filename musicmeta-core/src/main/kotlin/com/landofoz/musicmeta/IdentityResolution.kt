package com.landofoz.musicmeta

/**
 * Top-level identity resolution outcome for an enrichment request, on [EnrichmentResults.identity].
 *
 * Set by the engine exactly once per call, so a consumer never has to scan individual results to
 * learn whether MusicBrainz confirmed the entity. Always present — [status] carries every reason
 * resolution did not run, so nothing here is ever read as "absent means confident".
 */
data class IdentityResolution(
    /** Resolved identifiers (MBIDs, Wikidata, Wikipedia). */
    val identifiers: EnrichmentIdentifiers,
    /** Canonical resolution outcome for this call. */
    val status: CanonicalStatus,
    /**
     * Match score (0-100), same scale as [SearchCandidate.score]. Only set when [status] is
     * [CanonicalStatus.RESOLVED].
     *
     * **How well resolution went, not how sure we are it is the right entity.** A request carrying
     * an identifier resolves by looking it up, and that lookup succeeds or fails — so it scores 100
     * whether or not the identifier names what the caller described. Read [status] for that
     * question: [CanonicalStatus.CONTRADICTED] is the only field that reports a supplied identifier
     * naming something else, and [CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED] means nobody looked.
     * The same caveat applies to [EnrichmentResult.Success.confidence].
     */
    val matchScore: Int? = null,
    /**
     * Near-miss candidates when [status] is [CanonicalStatus.AMBIGUOUS].
     *
     * These are guesses offered for a caller to choose between, and they carry no claim that any
     * one of them is the requested entity — a candidate is never promoted to a resolution merely
     * because it ranked first. Ranking within a pool says which guess is best, never that a guess
     * is right, and the two are not the same when the pool was searched on an incomplete request.
     */
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
