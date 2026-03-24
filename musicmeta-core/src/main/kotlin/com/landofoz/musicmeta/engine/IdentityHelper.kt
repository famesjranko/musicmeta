package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.IdentityResolution

/** Builds the top-level [IdentityResolution] from the raw identity provider result. */
internal fun buildIdentityResolution(
    identityResult: EnrichmentResult?,
    enrichedRequest: EnrichmentRequest,
): IdentityResolution? = when (identityResult) {
    is EnrichmentResult.Success -> IdentityResolution(
        identifiers = identityResult.resolvedIdentifiers ?: enrichedRequest.identifiers,
        match = IdentityMatch.RESOLVED,
        matchScore = (identityResult.confidence * 100).toInt(),
    )
    is EnrichmentResult.NotFound -> IdentityResolution(
        identifiers = enrichedRequest.identifiers,
        match = if (identityResult.suggestions != null) IdentityMatch.SUGGESTIONS else IdentityMatch.BEST_EFFORT,
        matchScore = null,
        suggestions = identityResult.suggestions ?: emptyList(),
    )
    else -> null
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
        else -> {}
    }
}

/** Whether identity resolution is needed for the given request and types. */
internal fun needsIdentityResolution(
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    registry: ProviderRegistry,
): Boolean {
    val ids = request.identifiers
    if (ids.musicBrainzId == null && ids.musicBrainzReleaseGroupId == null) return true
    for (type in types) {
        val chain = registry.chainFor(type) ?: continue
        for (provider in chain.providers()) {
            val cap = provider.capabilities.firstOrNull { it.type == type } ?: continue
            val missing = when (cap.identifierRequirement) {
                IdentifierRequirement.NONE -> false
                IdentifierRequirement.MUSICBRAINZ_ID -> ids.musicBrainzId == null
                IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID -> ids.musicBrainzReleaseGroupId == null
                IdentifierRequirement.WIKIDATA_ID -> ids.wikidataId == null
                IdentifierRequirement.WIKIPEDIA_TITLE -> ids.wikipediaTitle == null && ids.wikidataId == null
                IdentifierRequirement.ANY_IDENTIFIER -> ids.musicBrainzId == null &&
                    ids.musicBrainzReleaseGroupId == null &&
                    ids.wikidataId == null && ids.wikipediaTitle == null
            }
            if (missing) return true
        }
    }
    return false
}
