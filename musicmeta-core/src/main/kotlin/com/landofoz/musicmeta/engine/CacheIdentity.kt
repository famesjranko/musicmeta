package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace

/** Entity kind carried by a provider-native identifier route. */
internal enum class EntityScope { ARTIST, ALBUM, TRACK }

/**
 * Builds the cache identity for a complete enrichment request.
 *
 * The cache key deliberately describes the request rather than guessing which provider route will
 * win. Providers may use any of the identifiers on a request, and a later provider or composite
 * can consume a field that was not part of an earlier route. Keeping the complete tuple makes a
 * mixed request replayable without allowing a single-id or name entry to answer it accidentally.
 */
internal object CacheIdentity {
    private const val VERSION = "musicmeta-cache-key-v2"

    /** Provider route helper used by providers; cache identity itself never depends on it. */
    data class ScopedProviderIdentifier(
        val namespace: IdentifierNamespace,
        val scope: EntityScope,
        val value: String,
    )

    /**
     * Returns an id only for provider branches that demonstrably consume the request id. Deezer's
     * discography branch searches by name, so its Deezer id is intentionally not trusted here.
     */
    fun trustedProviderIdentifier(request: EnrichmentRequest, type: EnrichmentType): ScopedProviderIdentifier? {
        val namespace = when (type) {
            EnrichmentType.TRACK_PREVIEW,
            EnrichmentType.TRACK_METADATA ->
                if (request is EnrichmentRequest.ForTrack) IdentifierNamespace.DEEZER else null
            EnrichmentType.ARTIST_TOP_TRACKS,
            EnrichmentType.SIMILAR_ARTISTS,
            EnrichmentType.ARTIST_RADIO ->
                if (request is EnrichmentRequest.ForArtist) IdentifierNamespace.DEEZER else null
            EnrichmentType.ARTIST_DISCOGRAPHY ->
                if (request is EnrichmentRequest.ForArtist) IdentifierNamespace.ITUNES_ARTIST else null
            else -> null
        } ?: return null
        val scope = when (request) {
            is EnrichmentRequest.ForArtist -> EntityScope.ARTIST
            is EnrichmentRequest.ForAlbum -> EntityScope.ALBUM
            is EnrichmentRequest.ForTrack -> EntityScope.TRACK
        }
        return request.identifiers.get(namespace)?.let { ScopedProviderIdentifier(namespace, scope, it) }
    }

    fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String =
        key(request, type, includeIdentifiers = true)

    /**
     * The canonical name key has exactly the same shape as the primary key, with identifiers
     * omitted. A name-only request therefore has one primary key, while an identity-resolved call
     * can still write a truthful canonical-name alias using this function.
     */
    fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
        key(request, type, includeIdentifiers = false)

    private fun key(request: EnrichmentRequest, type: EnrichmentType, includeIdentifiers: Boolean): String {
        val fields = buildList {
            add("scope" to scopeOf(request))
            add("type" to type.name)
            val ids = request.identifiers
            val hasIdentifiers = ids.musicBrainzId != null ||
                ids.musicBrainzReleaseGroupId != null ||
                ids.wikidataId != null || ids.isrc != null || ids.barcode != null ||
                ids.wikipediaTitle != null || ids.extra.isNotEmpty()
            if (includeIdentifiers && hasIdentifiers) {
                add("id.musicBrainzId" to ids.musicBrainzId)
                add("id.musicBrainzReleaseGroupId" to ids.musicBrainzReleaseGroupId)
                add("id.wikidataId" to ids.wikidataId)
                add("id.isrc" to ids.isrc)
                add("id.barcode" to ids.barcode)
                add("id.wikipediaTitle" to ids.wikipediaTitle)
                ids.extra.toSortedMap().forEach { (name, value) ->
                    add("id.extra.$name" to value)
                }
            }
            when (request) {
                is EnrichmentRequest.ForAlbum -> {
                    add("album.title" to request.title)
                    add("album.artist" to request.artist)
                    add("album.trackCount" to request.trackCount?.toString())
                    add("album.year" to request.year?.toString())
                }
                is EnrichmentRequest.ForArtist -> add("artist.name" to request.name)
                is EnrichmentRequest.ForTrack -> {
                    add("track.title" to request.title)
                    add("track.artist" to request.artist)
                    add("track.album" to request.album)
                    add("track.durationMs" to request.durationMs?.toString())
                }
            }
        }
        return VERSION + fields.joinToString(separator = "", transform = ::encodeField)
    }

    private fun scopeOf(request: EnrichmentRequest): String = when (request) {
        is EnrichmentRequest.ForAlbum -> "album"
        is EnrichmentRequest.ForArtist -> "artist"
        is EnrichmentRequest.ForTrack -> "track"
    }

    /** Length prefixes make labels, nulls, empties, and arbitrary user values unambiguous. */
    private fun encodeField(field: Pair<String, String?>): String {
        val (label, value) = field
        return encodePart(label) + encodePart(value)
    }

    private fun encodePart(value: String?): String =
        if (value == null) "-1:" else "${value.length}:$value"
}

internal typealias ScopedProviderIdentifier = CacheIdentity.ScopedProviderIdentifier

internal fun trustedProviderIdentifier(request: EnrichmentRequest, type: EnrichmentType): ScopedProviderIdentifier? =
    CacheIdentity.trustedProviderIdentifier(request, type)

internal fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String =
    CacheIdentity.entityKeyFor(request, type)

internal fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
    CacheIdentity.entityKeyForName(request, type)

internal fun namesNoEntity(request: EnrichmentRequest): Boolean = when (request) {
    is EnrichmentRequest.ForAlbum -> request.title.isBlank()
    is EnrichmentRequest.ForArtist -> request.name.isBlank()
    is EnrichmentRequest.ForTrack -> request.title.isBlank()
}

/**
 * True when an album or track request carries no artist, which leaves its name search unable to
 * identify an entity even though its title is present.
 *
 * Deliberately separate from [namesNoEntity] rather than folded into it: that predicate also
 * selects cache keys and routes identifier-only requests, so widening it would change which key a
 * request reads and writes. This one is read only by the guards that decide whether a name search
 * may claim an identity.
 *
 * An artist request is never blanked by this — its name *is* the artist, and [namesNoEntity]
 * already covers a blank one.
 */
internal fun artistBlanksNameSearch(request: EnrichmentRequest): Boolean = when (request) {
    is EnrichmentRequest.ForAlbum -> request.artist.isBlank()
    is EnrichmentRequest.ForTrack -> request.artist.isBlank()
    is EnrichmentRequest.ForArtist -> false
}
