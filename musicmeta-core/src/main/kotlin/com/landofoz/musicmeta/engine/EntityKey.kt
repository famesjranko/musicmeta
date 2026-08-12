package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType

/** Cache key using MBID when available, falling back to name. */
internal fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String {
    val prefix = entityPrefix(request)
    val id = request.identifiers.musicBrainzId ?: entityNamePart(request)
    return "$prefix:$id:$type"
}

/** Cache key using name/title only (no MBID), for cache aliasing after disambiguation. */
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
