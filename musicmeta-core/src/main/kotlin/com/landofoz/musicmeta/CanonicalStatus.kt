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
     * The identity provider was asked and errored (threw, or returned [EnrichmentResult.Error] or
     * [EnrichmentResult.RateLimited]) — typically transient, so retrying may resolve it. Distinct
     * from [UNRESOLVED], which is a genuine, completed search that found nothing.
     */
    FAILED,

    /** Identity resolution is turned off for this engine ([EnrichmentConfig.enableIdentityResolution]). */
    NOT_ATTEMPTED_DISABLED,

    /** The request already carried the identifiers every requested type needed; there was nothing to resolve. */
    NOT_ATTEMPTED_NOT_REQUIRED,

    /** Every requested type was served from cache; no live identity attempt ran this call. */
    NOT_ATTEMPTED_CACHE_HIT,

    /** Resolution was needed but no identity provider is registered on this engine. */
    NOT_ATTEMPTED_NO_PROVIDER,
}
