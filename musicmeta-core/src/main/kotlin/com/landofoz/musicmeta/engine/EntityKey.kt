package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace

/**
 * Provider ids trusted to name the request's own entity, keyed by the [EnrichmentType] each is
 * trusted for. [IdentifierNamespace.DEEZER] is polymorphic — `SimilarAlbumsProvider` reads the same
 * key as a seed *artist* id on an album request — so a (request kind, type) pair absent here falls
 * back to the name key rather than guessing a scope. Extend per audited tuple, never by namespace
 * alone.
 */
private val TRACK_PROVIDER_IDENTITY: Map<EnrichmentType, IdentifierNamespace> =
    mapOf(EnrichmentType.TRACK_PREVIEW to IdentifierNamespace.DEEZER)

/** The provider-id part of a cache key for [request]/[type], or null when none is trusted here. */
private fun providerIdPart(request: EnrichmentRequest, type: EnrichmentType): String? {
    if (request !is EnrichmentRequest.ForTrack) return null
    val ns = TRACK_PROVIDER_IDENTITY[type] ?: return null
    val id = request.identifiers.get(ns) ?: return null
    return "${ns.name.lowercase()}:$id"
}

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
