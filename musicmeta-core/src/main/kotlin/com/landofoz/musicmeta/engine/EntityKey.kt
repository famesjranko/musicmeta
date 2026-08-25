package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.LookupProvenance

/**
 * [LookupProvenance] for a [type] result that did not report its own route, from what the winning
 * provider's chain walk actually required to run it — never from which identifiers merely happen to
 * be present on the request, which a provider that used none of them may still have satisfied by
 * coincidence. [winningRequirement] is [ChainExecution.winningRequirement]: null when no single
 * provider's `Success` was captured this way, e.g. a merged multi-provider result. [stampProvenanceOne]
 * only reaches this function for a `null` [EnrichmentResult.Success.provenance] — a provider that
 * sets its own route on the `Success` it returns is trusted verbatim and never reaches here.
 *
 * A provider whose declared requirement demands *some* MusicBrainz-issued id ([IdentifierRequirement.MUSICBRAINZ_ID],
 * [IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID]) could only have run by consuming one, so that
 * is [LookupProvenance.CANONICAL_ID] — observed, not inferred. A requirement naming exactly one
 * provider-owned id space ([IdentifierRequirement.WIKIDATA_ID], [IdentifierRequirement.WIKIPEDIA_TITLE])
 * is [LookupProvenance.PROVIDER_NATIVE_ID] on the same basis. [IdentifierRequirement.ANY_IDENTIFIER] is
 * deliberately excluded from that bucket: it accepts a MusicBrainz id, a release-group id, a Wikidata
 * id, or a Wikipedia title, so the requirement alone cannot say which id space actually ran — same as
 * [IdentifierRequirement.NONE] and a missing [winningRequirement], where a name was searched and only
 * [nameEvidence] can tell a confirmed name from an unverified guess.
 *
 * [nameEvidence] is what identity resolution established about *the name this fan-out searched*, from
 * [DefaultEnrichmentEngine.identityNameEvidence]: a name provenance when MusicBrainz vouched for that
 * name, and `null` when it did not. It is deliberately not [CanonicalStatus]. A status of
 * [CanonicalStatus.RESOLVED] can be reached by looking an identifier up, which confirms that the
 * identifier names something and says nothing whatever about the caller's name — reading it as proof
 * of a name match stamps [LookupProvenance.EXACT_NAME] on a search MusicBrainz never confirmed.
 * `CanonicalStatus`'s own KDoc already says it describes canonical resolution rather than a provider
 * result's trustworthiness; deriving this from it contradicted that.
 */
internal fun observedProvenance(
    winningRequirement: IdentifierRequirement?,
    nameEvidence: LookupProvenance?,
): LookupProvenance = when (winningRequirement) {
    IdentifierRequirement.MUSICBRAINZ_ID, IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID ->
        LookupProvenance.CANONICAL_ID
    IdentifierRequirement.WIKIDATA_ID, IdentifierRequirement.WIKIPEDIA_TITLE ->
        LookupProvenance.PROVIDER_NATIVE_ID
    IdentifierRequirement.NONE, IdentifierRequirement.ANY_IDENTIFIER, null -> when (nameEvidence) {
        // Only a *name* provenance vouches for a name. Anything else - a resolved identifier most
        // of all - says nothing about the name this provider searched, so it is an unverified guess.
        LookupProvenance.EXACT_NAME, LookupProvenance.QUALIFIER_FALLBACK_NAME -> nameEvidence
        else -> LookupProvenance.FUZZY_NAME
    }
}

/**
 * Explicit strength order for [LookupProvenance], strongest first — restated from the enum's own
 * declaration order so a merged or composite result's summary provenance does not silently drift if
 * that declaration order ever changes for an unrelated reason. See [weakestProvenance].
 */
private val PROVENANCE_STRENGTH: List<LookupProvenance> = listOf(
    LookupProvenance.CANONICAL_ID,
    LookupProvenance.PROVIDER_NATIVE_ID,
    LookupProvenance.EXTERNAL_CATALOG_ID,
    LookupProvenance.EXACT_NAME,
    LookupProvenance.QUALIFIER_FALLBACK_NAME,
    LookupProvenance.FUZZY_NAME,
    LookupProvenance.CACHE,
)

/**
 * The least-confident value among [provenances] under [PROVENANCE_STRENGTH] — the smallest truthful
 * summary of several contributors' routes for a merged or composite result, none of which has a
 * singular observed route of its own. Every contributor's own evidence is at least this strong, so a
 * consumer reading the summary alone never overtrusts it. [provenances] is expected non-empty by
 * every caller (a merge/synthesis with zero successful contributors returns `NotFound` before this is
 * reached); [FUZZY_NAME] is the defensive fallback for the unreachable empty case, not a claim.
 */
internal fun weakestProvenance(provenances: List<LookupProvenance>): LookupProvenance =
    provenances.maxByOrNull { PROVENANCE_STRENGTH.indexOf(it) } ?: LookupProvenance.FUZZY_NAME

/**
 * [observedProvenance] for one contributor to a mergeable type's collect-all walk, by that
 * contributor's own provider — never [ChainExecution.winningRequirement], which a collect-all walk
 * never sets because it has no single winner. Self-reported [success]es are trusted verbatim, same
 * as [stampProvenanceOne]. [chain] is null only when the type has no registered chain at all, in which
 * case there is no provider to ask and [IdentifierRequirement.NONE] applies.
 */
internal fun stampContributorProvenance(
    success: EnrichmentResult.Success,
    chain: ProviderChain?,
    nameEvidence: LookupProvenance?,
): EnrichmentResult.Success {
    if (success.provenance != null) return success
    val requirement = chain?.requirementForProviderId(success.provider) ?: IdentifierRequirement.NONE
    return success.copy(provenance = observedProvenance(requirement, nameEvidence))
}
