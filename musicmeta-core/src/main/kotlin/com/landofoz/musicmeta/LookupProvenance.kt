package com.landofoz.musicmeta

/**
 * How a single provider selected the entity behind one [EnrichmentResult.Success], on
 * [EnrichmentResult.Success.provenance]. Set once per successful result — it describes that
 * provider's own lookup, not whether MusicBrainz canonically agreed; see
 * [IdentityResolution.status] for the canonical fact.
 *
 * A cache implementation may preserve this field with the stored result. When it cannot recover
 * the original route, the engine marks the hit [CACHE]; a cache hit is never treated as a new
 * provider lookup.
 *
 * A merged or synthesized result carries the weakest contributor's value, on the strength order
 * declared alongside that rule rather than this enum's declaration order.
 *
 * @see com.landofoz.musicmeta.engine.weakestProvenance
 */
enum class LookupProvenance {
    /** Looked up directly by a MusicBrainz canonical id (MBID or release-group id). */
    CANONICAL_ID,

    /** Looked up directly by a provider-native id supplied on the request (e.g. a Deezer track id). */
    PROVIDER_NATIVE_ID,

    /**
     * Looked up directly by an external catalogue identifier supplied on the request (e.g. a UPC
     * barcode) — a direct-lookup id, but neither a MusicBrainz id nor a provider's own id space.
     */
    EXTERNAL_CATALOG_ID,

    /**
     * Selected by a name search whose hit came back under the name that was asked for — the two
     * were compared, not merely scored against each other.
     */
    EXACT_NAME,

    /** Selected by a name search after normalization or qualifier-fallback stripping. */
    QUALIFIER_FALLBACK_NAME,

    /**
     * Selected by an unverified name search: nothing confirmed the name, or the hit came back
     * under a different name from the one that was asked for.
     */
    FUZZY_NAME,

    /** Served from cache by an implementation that did not preserve the original provenance. */
    CACHE,
}
