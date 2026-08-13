package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.LookupProvenance

/** The kind of entity a [ScopedProviderIdentifier]'s value names. */
internal enum class EntityScope { ARTIST, ALBUM, TRACK }

/**
 * A provider-native identifier paired with the entity kind it names. A bare
 * [IdentifierNamespace]/value pair cannot say that on its own — [IdentifierNamespace.DEEZER] is
 * polymorphic, and `SimilarAlbumsProvider` reads the same namespace as a seed *artist* id on an
 * album request — so a reader that is about to trust a namespace value as naming one particular
 * entity carries this instead.
 */
internal data class ScopedProviderIdentifier(
    val namespace: IdentifierNamespace,
    val scope: EntityScope,
    val value: String,
)

/** A [ScopedProviderIdentifier] namespace/scope pair, before the request supplies its value. */
private data class ProviderIdentity(val namespace: IdentifierNamespace, val scope: EntityScope)

/**
 * Provider ids trusted to name the request's own entity, keyed by the [EnrichmentType] each is
 * trusted for. A namespace being present on a request says nothing about which entity it names —
 * [IdentifierNamespace.DEEZER] is polymorphic, and `SimilarAlbumsProvider` reads that same namespace
 * as a seed *artist* id on an *album* request, which must never be trusted as that request's own
 * album identity — so a (request kind, type) pair absent here falls back to the name key rather than
 * guessing a scope. Extend per audited tuple, never by namespace alone: each entry below is a
 * provider branch that reads exactly this namespace as this exact type's own request entity, with no
 * other capability for the same type reading a different id space as that same entity.
 */
private val PROVIDER_IDENTITY: Map<EnrichmentType, ProviderIdentity> = mapOf(
    EnrichmentType.TRACK_PREVIEW to ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.TRACK),
    EnrichmentType.TRACK_METADATA to ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.TRACK),
    EnrichmentType.ARTIST_TOP_TRACKS to ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST),
    EnrichmentType.SIMILAR_ARTISTS to ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST),
    EnrichmentType.ARTIST_RADIO to ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST),
    EnrichmentType.ARTIST_DISCOGRAPHY to ProviderIdentity(IdentifierNamespace.ITUNES_ARTIST, EntityScope.ARTIST),
)

private fun EnrichmentRequest.entityScope(): EntityScope = when (this) {
    is EnrichmentRequest.ForArtist -> EntityScope.ARTIST
    is EnrichmentRequest.ForAlbum -> EntityScope.ALBUM
    is EnrichmentRequest.ForTrack -> EntityScope.TRACK
}

/**
 * The provider-native identifier this exact [type] lookup for [request] is trusted to use, or null
 * — the same audited tuple [entityKeyFor]'s provider-id tier keys on. Exposed so a provider
 * reporting its own [LookupProvenance] evidence reads the same trusted scope instead of re-deriving
 * one from a bare namespace lookup.
 */
internal fun trustedProviderIdentifier(request: EnrichmentRequest, type: EnrichmentType): ScopedProviderIdentifier? {
    val identity = PROVIDER_IDENTITY[type] ?: return null
    if (request.entityScope() != identity.scope) return null
    val id = request.identifiers.get(identity.namespace) ?: return null
    return ScopedProviderIdentifier(identity.namespace, identity.scope, id)
}

/** The provider-id part of a cache key for [request]/[type], or null when none is trusted here. */
private fun providerIdPart(request: EnrichmentRequest, type: EnrichmentType): String? =
    trustedProviderIdentifier(request, type)?.let { "${it.namespace.name.lowercase()}:${it.value}" }

/**
 * Cache key selected in priority order: the canonical MusicBrainz id, then a provider id trusted
 * for this exact [type] (see [TRACK_PROVIDER_IDENTITY]), then the bare name.
 */
internal fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String {
    val prefix = entityPrefix(request)
    val id = request.identifiers.musicBrainzId
        ?: providerIdPart(request, type)
        ?: entityNamePart(request)
    return "$prefix:$id:$type"
}

/** Cache key using name/title only (no MBID or provider id), for cache aliasing after disambiguation. */
internal fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
    "${entityPrefix(request)}:${entityNamePart(request)}:$type"

/**
 * [LookupProvenance] for a [type] result that did not report its own route, from what the winning
 * provider's chain walk actually required to run it — never from which identifiers merely happen to
 * be present on the request, which a provider that used none of them may still have satisfied by
 * coincidence. [winningRequirement] is [ChainExecution.winningRequirement]: null when no single
 * provider's `Success` was captured this way, e.g. a merged multi-provider result. [stampProvenance]
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
 * [IdentifierRequirement.NONE] and a missing [winningRequirement], only [canonicalStatus] — MusicBrainz's
 * canonical confirmation of this call — is left to tell an exact name match from an unverified guess.
 */
internal fun observedProvenance(
    winningRequirement: IdentifierRequirement?,
    canonicalStatus: CanonicalStatus,
): LookupProvenance = when (winningRequirement) {
    IdentifierRequirement.MUSICBRAINZ_ID, IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID ->
        LookupProvenance.CANONICAL_ID
    IdentifierRequirement.WIKIDATA_ID, IdentifierRequirement.WIKIPEDIA_TITLE ->
        LookupProvenance.PROVIDER_NATIVE_ID
    IdentifierRequirement.NONE, IdentifierRequirement.ANY_IDENTIFIER, null ->
        if (canonicalStatus == CanonicalStatus.RESOLVED) LookupProvenance.EXACT_NAME else LookupProvenance.FUZZY_NAME
}

/**
 * Explicit strength order for [LookupProvenance], strongest first — restated from the enum's own
 * declaration order so a merged or composite result's summary provenance does not silently drift if
 * that declaration order ever changes for an unrelated reason. See [weakestProvenance].
 */
private val PROVENANCE_STRENGTH: List<LookupProvenance> = listOf(
    LookupProvenance.CANONICAL_ID,
    LookupProvenance.PROVIDER_NATIVE_ID,
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
 * as [stampProvenance]. [chain] is null only when the type has no registered chain at all, in which
 * case there is no provider to ask and [IdentifierRequirement.NONE] applies.
 */
internal fun stampContributorProvenance(
    success: EnrichmentResult.Success,
    chain: ProviderChain?,
    canonicalStatus: CanonicalStatus,
): EnrichmentResult.Success {
    if (success.provenance != null) return success
    val requirement = chain?.requirementForProviderId(success.provider) ?: IdentifierRequirement.NONE
    return success.copy(provenance = observedProvenance(requirement, canonicalStatus))
}

/**
 * Whether [request] names no entity — an [EnrichmentRequest.Companion.forTrackByMbid]-style request
 * before identity resolution has filled it in. Its name key names nothing until then.
 *
 * The title (or artist name) alone decides it. A blank *artist* beside a real title is not this
 * case: MusicBrainz drops an empty `artistname:""` term and resolves on the title, verified live
 * 2026-08-12 (`recording:"Bohemian Rhapsody" AND artistname:""` → count 823, top hits at score 100),
 * so such a request still has a search worth making.
 */
internal fun namesNoEntity(request: EnrichmentRequest): Boolean = when (request) {
    is EnrichmentRequest.ForAlbum -> request.title.isBlank()
    is EnrichmentRequest.ForArtist -> request.name.isBlank()
    is EnrichmentRequest.ForTrack -> request.title.isBlank()
}

private fun entityPrefix(request: EnrichmentRequest): String = when (request) {
    is EnrichmentRequest.ForAlbum -> "album"
    is EnrichmentRequest.ForArtist -> "artist"
    is EnrichmentRequest.ForTrack -> "track"
}

private fun entityNamePart(request: EnrichmentRequest): String = when (request) {
    is EnrichmentRequest.ForAlbum -> "${request.artist}:${request.title}"
    is EnrichmentRequest.ForArtist -> request.name
    is EnrichmentRequest.ForTrack -> "${request.artist}:${request.title}"
}
