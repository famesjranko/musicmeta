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
