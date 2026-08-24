package com.landofoz.musicmeta

/**
 * MusicBrainz canonical resolution outcome for one [EnrichmentEngine.enrich] call, on
 * [IdentityResolution.status]. Set exactly once per call — never `null`, so a consumer can branch
 * on it directly instead of treating an absent value as confident.
 *
 * This describes only whether MusicBrainz confirmed the entity the request named. It says nothing
 * about whether any individual provider's result is trustworthy — see [LookupProvenance] for that.
 */
enum class CanonicalStatus {
    /** MusicBrainz confirmed the entity. [IdentityResolution.matchScore] has the score. */
    RESOLVED,

    /** MusicBrainz could not confirm the entity but offered near-miss candidates. See [IdentityResolution.suggestions]. */
    AMBIGUOUS,

    /** MusicBrainz was searched and returned neither a match nor candidates. */
    UNRESOLVED,

    /**
     * An identifier on the request named an entity confidently different from the one the request
     * described — a MusicBrainz id for one artist beside another artist's name. The identifier is
     * not the requested entity, and any data looked up under it was discarded.
     *
     * **Outranks every other status, including [RESOLVED].** A request that carries a usable name
     * falls back to searching it, so the results beside this status may be complete and correct and
     * carry a name-route [LookupProvenance]; `CONTRADICTED` alongside
     * [LookupProvenance.EXACT_NAME] means "your identifier was wrong, and the name recovered the
     * entity anyway". Reporting the fallback's success instead would hide the bad identifier, which
     * is the one thing here the caller cannot find out any other way.
     *
     * Contradiction requires positive evidence of disagreement. Names that merely could not be
     * equated — a script this cannot compare, an unfamiliar spelling — are not contradictions.
     */
    CONTRADICTED,

    /**
     * The identity provider was asked and errored (threw, or returned [EnrichmentResult.Error] or
     * [EnrichmentResult.RateLimited]) — typically transient, so retrying may resolve it. Distinct
     * from [UNRESOLVED], which is a genuine, completed search that found nothing.
     */
    FAILED,

    /**
     * Identity resolution is running for this call and has not settled yet. Only ever seen on a
     * pre-terminal [EnrichmentEngine.enrichProgressive] emission — never on [EnrichmentEngine.enrich]'s
     * return or on the terminal emission of the same stream, both of which wait for resolution to
     * finish before reporting a real status.
     */
    RESOLVING,

    /** Identity resolution is turned off for this engine ([EnrichmentConfig.enableIdentityResolution]). */
    NOT_ATTEMPTED_DISABLED,

    /**
     * The request carried a MusicBrainz identifier and every requested type was content with it, so
     * no resolution ran.
     *
     * **Trusted, not verified.** Nothing checked that the identifier names the entity the request
     * describes; that check is [CONTRADICTED]'s, and it only reports the cases it can prove wrong.
     * Treat this as the caller's own assertion carried through, not as MusicBrainz agreeing with it.
     */
    NOT_ATTEMPTED_IDENTIFIER_TRUSTED,

    /** Every requested type was served from cache; no live identity attempt ran this call. */
    NOT_ATTEMPTED_CACHE_HIT,

    /** Resolution was needed but no identity provider is registered on this engine. */
    NOT_ATTEMPTED_NO_PROVIDER,
}
