package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.IdentityResolution

/**
 * Builds the top-level [IdentityResolution] from the raw identity provider result.
 *
 * [names] is what the identity provider read off the entity a caller's **identifier** named, and
 * the only source for [IdentityResolution.title]/[IdentityResolution.artist] — including on a
 * request that carried names of its own alongside that identifier, whose canonical forms the
 * caller cannot otherwise see. A request naming an entity with no identifier resolves by name
 * search instead, and [names] stays null: a search hit is what a name matched, not what an
 * identifier named, so it never backfills these fields. A resolution that named nothing leaves
 * both null rather than echoing the request back as if it had been confirmed.
 *
 * [notAttemptedStatus] is only consulted when [identityResult] is `null` — the caller has already
 * worked out *why* nothing ran (disabled, not required, or no provider registered) since that
 * reason is not recoverable from a bare `null`.
 */
internal fun buildIdentityResolution(
    identityResult: EnrichmentResult?,
    enrichedRequest: EnrichmentRequest,
    names: EntityNames?,
    notAttemptedStatus: CanonicalStatus,
): IdentityResolution = when (identityResult) {
    is EnrichmentResult.Success -> IdentityResolution(
        identifiers = identityResult.resolvedIdentifiers ?: enrichedRequest.identifiers,
        status = CanonicalStatus.RESOLVED,
        matchScore = (identityResult.confidence * 100).toInt(),
        title = names?.title,
        artist = names?.artist,
    )
    is EnrichmentResult.NotFound -> IdentityResolution(
        identifiers = enrichedRequest.identifiers,
        status = if (identityResult.suggestions != null) CanonicalStatus.AMBIGUOUS else CanonicalStatus.UNRESOLVED,
        suggestions = identityResult.suggestions.orEmpty(),
        title = names?.title,
        artist = names?.artist,
    )
    // Error / RateLimited: the provider was asked and failed, which must stay distinguishable
    // from a not-attempted status — a transient failure is not a confident match.
    is EnrichmentResult.Error, is EnrichmentResult.RateLimited -> IdentityResolution(
        identifiers = enrichedRequest.identifiers,
        status = CanonicalStatus.FAILED,
        title = names?.title,
        artist = names?.artist,
    )
    null -> IdentityResolution(identifiers = enrichedRequest.identifiers, status = notAttemptedStatus)
}

/**
 * Stamps [com.landofoz.musicmeta.LookupProvenance] on every freshly resolved [EnrichmentResult.Success]
 * that did not already report its own route. [executions] carries the [ChainExecution] each type's
 * chain walk produced — the provider execution evidence [observedProvenance] classifies from, rather
 * than which identifiers merely happen to be present on [request].
 */
internal fun stampProvenance(
    results: MutableMap<EnrichmentType, EnrichmentResult>,
    canonicalStatus: CanonicalStatus,
    executions: Map<EnrichmentType, ChainExecution>,
) {
    for ((type, result) in results) {
        if (result is EnrichmentResult.Success && result.provenance == null) {
            val provenance = observedProvenance(executions[type]?.winningRequirement, canonicalStatus)
            results[type] = result.copy(provenance = provenance)
        }
    }
}

/**
 * Whether identity resolution is needed for the given request and types.
 *
 * [EnrichmentIdentifiers.musicBrainzId] is polymorphic — a release id on
 * [EnrichmentRequest.ForAlbum], an artist id on [EnrichmentRequest.ForArtist], a recording id on
 * [EnrichmentRequest.ForTrack] — so [IdentifierRequirement.MUSICBRAINZ_ID] means "an id of the
 * request's own kind", and a capability declaring it says nothing about which kind it can serve.
 * On a track request that gap is real and reachable: cover art is keyed on a release-group, a
 * recording id cannot stand in for one, and nothing but resolution fills
 * [EnrichmentIdentifiers.musicBrainzReleaseGroupId]. Without the track clause below, supplying a
 * correct recording MBID returns strictly less than supplying nothing.
 */
internal fun needsIdentityResolution(
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    registry: ProviderRegistry,
): Boolean {
    // A request naming only an identifier has no name of its own, and every provider but the
    // identity one searches by name — so resolution is what makes the fan-out possible at all,
    // whatever the requested types declare.
    if (namesNoEntity(request)) return true
    val ids = request.identifiers
    if (ids.musicBrainzId == null && ids.musicBrainzReleaseGroupId == null) return true
    // Qualifies MUSICBRAINZ_ID below rather than short-circuiting here: a track request whose types
    // declare no identifier at all still has nothing to resolve, and must not pay for a lookup.
    val trackIdIsRecordingOnly =
        request is EnrichmentRequest.ForTrack && ids.musicBrainzReleaseGroupId == null
    for (type in types) {
        val chain = registry.chainFor(type) ?: continue
        for (provider in chain.providers()) {
            val cap = provider.capabilities.firstOrNull { it.type == type } ?: continue
            val missing = when (cap.identifierRequirement) {
                IdentifierRequirement.NONE -> false
                IdentifierRequirement.MUSICBRAINZ_ID -> ids.musicBrainzId == null || trackIdIsRecordingOnly
                IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID -> ids.musicBrainzReleaseGroupId == null
                IdentifierRequirement.WIKIDATA_ID -> ids.wikidataId == null
                IdentifierRequirement.WIKIPEDIA_TITLE -> ids.wikipediaTitle == null && ids.wikidataId == null
                IdentifierRequirement.ANY_IDENTIFIER -> ids.musicBrainzId == null &&
                    ids.musicBrainzReleaseGroupId == null &&
                    ids.wikidataId == null &&
                    ids.wikipediaTitle == null
            }
            if (missing) return true
        }
    }
    return false
}
