package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentRequest
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

/**
 * Provider ids trusted to name the request's own entity, keyed by the [EnrichmentType] each is
 * trusted for. [IdentifierNamespace.DEEZER] is polymorphic — `SimilarAlbumsProvider` reads the same
 * key as a seed *artist* id on an album request — so a (request kind, type) pair absent here falls
 * back to the name key rather than guessing a scope. Extend per audited tuple, never by namespace
 * alone.
 */
private val TRACK_PROVIDER_IDENTITY: Map<EnrichmentType, IdentifierNamespace> =
    mapOf(EnrichmentType.TRACK_PREVIEW to IdentifierNamespace.DEEZER)

/**
 * The provider-native identifier this exact [type] lookup for [request] is trusted to use, or null
 * — the same audited tuple [entityKeyFor]'s provider-id tier keys on. Exposed so a provider
 * reporting its own [LookupProvenance] evidence reads the same trusted scope instead of re-deriving
 * one from a bare namespace lookup.
 */
internal fun trustedProviderIdentifier(request: EnrichmentRequest, type: EnrichmentType): ScopedProviderIdentifier? {
    if (request !is EnrichmentRequest.ForTrack) return null
    val ns = TRACK_PROVIDER_IDENTITY[type] ?: return null
    val id = request.identifiers.get(ns) ?: return null
    return ScopedProviderIdentifier(ns, EntityScope.TRACK, id)
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
 * is [LookupProvenance.CANONICAL_ID] — observed, not inferred. A requirement naming a provider's own
 * id space ([IdentifierRequirement.WIKIDATA_ID], [IdentifierRequirement.WIKIPEDIA_TITLE],
 * [IdentifierRequirement.ANY_IDENTIFIER]) is [LookupProvenance.PROVIDER_NATIVE_ID] on the same basis.
 * [IdentifierRequirement.NONE] and a missing [winningRequirement] both mean the route cannot be
 * decided from what running required; only [canonicalStatus] — MusicBrainz's canonical confirmation
 * of this call — is left to tell an exact name match from an unverified guess.
 */
internal fun observedProvenance(
    winningRequirement: IdentifierRequirement?,
    canonicalStatus: CanonicalStatus,
): LookupProvenance = when (winningRequirement) {
    IdentifierRequirement.MUSICBRAINZ_ID, IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID ->
        LookupProvenance.CANONICAL_ID
    IdentifierRequirement.WIKIDATA_ID, IdentifierRequirement.WIKIPEDIA_TITLE, IdentifierRequirement.ANY_IDENTIFIER ->
        LookupProvenance.PROVIDER_NATIVE_ID
    IdentifierRequirement.NONE, null ->
        if (canonicalStatus == CanonicalStatus.RESOLVED) LookupProvenance.EXACT_NAME else LookupProvenance.FUZZY_NAME
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
