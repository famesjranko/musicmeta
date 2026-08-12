package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.IdentityResolution

/**
 * Builds the top-level [IdentityResolution] from the raw identity provider result.
 *
 * [names] is what the identity provider read off the entity it looked up, and the only source for
 * [IdentityResolution.title]/[IdentityResolution.artist] — including on a request that carried
 * names of its own, whose canonical forms the caller cannot otherwise see. A resolution that named
 * nothing leaves both null rather than echoing the request back as if it had been confirmed.
 */
internal fun buildIdentityResolution(
    identityResult: EnrichmentResult?,
    enrichedRequest: EnrichmentRequest,
    names: EntityNames?,
): IdentityResolution? = when (identityResult) {
    is EnrichmentResult.Success -> IdentityResolution(
        identifiers = identityResult.resolvedIdentifiers ?: enrichedRequest.identifiers,
        match = IdentityMatch.RESOLVED,
        matchScore = (identityResult.confidence * 100).toInt(),
        title = names?.title,
        artist = names?.artist,
    )
    is EnrichmentResult.NotFound -> IdentityResolution(
        identifiers = enrichedRequest.identifiers,
        match = if (identityResult.suggestions != null) IdentityMatch.SUGGESTIONS else IdentityMatch.BEST_EFFORT,
        matchScore = null,
        suggestions = identityResult.suggestions.orEmpty(),
        title = names?.title,
        artist = names?.artist,
    )
    // Error / RateLimited: the provider was asked and failed, which must stay distinguishable
    // from null ("resolution was not attempted") — a transient failure is not a confident match.
    is EnrichmentResult.Error, is EnrichmentResult.RateLimited -> IdentityResolution(
        identifiers = enrichedRequest.identifiers,
        match = IdentityMatch.UNVERIFIED,
        matchScore = null,
        title = names?.title,
        artist = names?.artist,
    )
    null -> null
}

/** Stamps [IdentityMatch] and score on all freshly resolved results. */
internal fun stampIdentityMatch(
    results: MutableMap<EnrichmentType, EnrichmentResult>,
    identityResult: EnrichmentResult?,
) {
    when (identityResult) {
        is EnrichmentResult.Success -> {
            val score = (identityResult.confidence * 100).toInt()
            for ((type, result) in results) {
                if (result is EnrichmentResult.Success && result.identityMatch == null) {
                    results[type] = result.copy(identityMatch = IdentityMatch.RESOLVED, identityMatchScore = score)
                }
            }
        }
        is EnrichmentResult.NotFound -> {
            for ((type, result) in results) {
                if (result is EnrichmentResult.Success && result.identityMatch == null) {
                    results[type] = result.copy(identityMatch = IdentityMatch.BEST_EFFORT)
                }
            }
        }
        is EnrichmentResult.Error, is EnrichmentResult.RateLimited -> {
            for ((type, result) in results) {
                if (result is EnrichmentResult.Success && result.identityMatch == null) {
                    results[type] = result.copy(identityMatch = IdentityMatch.UNVERIFIED)
                }
            }
        }
        null -> {}
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
