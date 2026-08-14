package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace

/** The request entity kind required to interpret a provider-native namespace safely. */
internal enum class EntityScope { ARTIST, ALBUM, TRACK }

/**
 * The cache identity policy for an enrichment request. Provider route knowledge and composite
 * dependency expansion live here so the engine only asks one seam whether a key is safe.
 */
internal object CacheIdentity {

    /** A provider-native identifier paired with the entity kind it names. */
    data class ScopedProviderIdentifier(
        val namespace: IdentifierNamespace,
        val scope: EntityScope,
        val value: String,
    )

    private data class ProviderIdentity(val namespace: IdentifierNamespace, val scope: EntityScope)

    /**
     * Audited request-kind/type/provider branches that consume a request identifier as the entity
     * they enrich. Multiple entries for one type are intentional: ARTIST_DISCOGRAPHY has both
     * iTunes and Deezer native artist routes. A namespace alone is never enough because Deezer is
     * polymorphic across artist and track requests.
     */
    private val providerIdentities: Map<EnrichmentType, List<ProviderIdentity>> = mapOf(
        EnrichmentType.TRACK_PREVIEW to listOf(ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.TRACK)),
        EnrichmentType.TRACK_METADATA to listOf(ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.TRACK)),
        EnrichmentType.ARTIST_TOP_TRACKS to listOf(ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST)),
        EnrichmentType.SIMILAR_ARTISTS to listOf(ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST)),
        EnrichmentType.ARTIST_RADIO to listOf(ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST)),
        EnrichmentType.ARTIST_DISCOGRAPHY to listOf(
            ProviderIdentity(IdentifierNamespace.ITUNES_ARTIST, EntityScope.ARTIST),
            ProviderIdentity(IdentifierNamespace.DEEZER, EntityScope.ARTIST),
        ),
    )

    /** The first applicable route preserves one provider's existing direct lookup choice. */
    fun trustedProviderIdentifier(request: EnrichmentRequest, type: EnrichmentType): ScopedProviderIdentifier? {
        val scope = request.entityScope()
        return providerIdentities[type].orEmpty()
            .asSequence()
            .filter { it.scope == scope }
            .mapNotNull { identity ->
                request.identifiers.get(identity.namespace)?.let {
                    ScopedProviderIdentifier(identity.namespace, identity.scope, it)
                }
            }
            .firstOrNull()
    }

    /** Whether a type or dependency has more than one applicable exact identity token. */
    fun hasUnvalidatedMixedIdentity(
        request: EnrichmentRequest,
        type: EnrichmentType,
        compositeDependencies: Map<EnrichmentType, Set<EnrichmentType>> = emptyMap(),
    ): Boolean {
        val nativeTokens = expandedTypes(type, compositeDependencies)
            .flatMap { candidate -> providerIdentities[candidate].orEmpty() }
            .filter { it.scope == request.entityScope() }
            .mapNotNull { identity ->
                request.identifiers.get(identity.namespace)?.let { identity.namespace to it }
            }
            .toSet()
        val exactIdentityCount = nativeTokens.size + if (request.identifiers.musicBrainzId != null) 1 else 0
        return exactIdentityCount > 1
    }

    fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String {
        val prefix = entityPrefix(request)
        val id = request.identifiers.musicBrainzId
            ?: trustedProviderIdentifier(request, type)?.let { "${it.namespace.name.lowercase()}:${it.value}" }
            ?: encodedNamePart(request)
        return "$prefix:$id:$type"
    }

    fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
        "${entityPrefix(request)}:${encodedNamePart(request)}:$type"

    /**
     * Expands all transitive synthesizer inputs. A key is unsafe when its type graph can route via
     * more than one exact token, because no route has proved those tokens name one entity.
     */
    private fun expandedTypes(
        type: EnrichmentType,
        compositeDependencies: Map<EnrichmentType, Set<EnrichmentType>>,
    ): Set<EnrichmentType> {
        val seen = linkedSetOf<EnrichmentType>()
        fun visit(candidate: EnrichmentType) {
            if (!seen.add(candidate)) return
            compositeDependencies[candidate].orEmpty().forEach(::visit)
        }
        visit(type)
        return seen
    }

    private fun EnrichmentRequest.entityScope(): EntityScope = when (this) {
        is EnrichmentRequest.ForArtist -> EntityScope.ARTIST
        is EnrichmentRequest.ForAlbum -> EntityScope.ALBUM
        is EnrichmentRequest.ForTrack -> EntityScope.TRACK
    }

    private fun entityPrefix(request: EnrichmentRequest): String = when (request) {
        is EnrichmentRequest.ForAlbum -> "album"
        is EnrichmentRequest.ForArtist -> "artist"
        is EnrichmentRequest.ForTrack -> "track"
    }

    private fun encodedNamePart(request: EnrichmentRequest): String = when (request) {
        is EnrichmentRequest.ForAlbum -> "${encode(request.artist)}:${encode(request.title)}"
        is EnrichmentRequest.ForArtist -> encode(request.name)
        is EnrichmentRequest.ForTrack -> "${encode(request.artist)}:${encode(request.title)}"
    }

    /** Escaping both '%' and ':' makes the existing delimiter format unambiguous and stable. */
    private fun encode(value: String): String = value.replace("%", "%25").replace(":", "%3A")
}

internal typealias ScopedProviderIdentifier = CacheIdentity.ScopedProviderIdentifier

internal fun trustedProviderIdentifier(request: EnrichmentRequest, type: EnrichmentType): ScopedProviderIdentifier? =
    CacheIdentity.trustedProviderIdentifier(request, type)

internal fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String =
    CacheIdentity.entityKeyFor(request, type)

internal fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
    CacheIdentity.entityKeyForName(request, type)

internal fun hasUnvalidatedMixedIdentity(
    request: EnrichmentRequest,
    type: EnrichmentType,
    compositeDependencies: Map<EnrichmentType, Set<EnrichmentType>> = emptyMap(),
): Boolean = CacheIdentity.hasUnvalidatedMixedIdentity(request, type, compositeDependencies)

internal fun namesNoEntity(request: EnrichmentRequest): Boolean = when (request) {
    is EnrichmentRequest.ForAlbum -> request.title.isBlank()
    is EnrichmentRequest.ForArtist -> request.name.isBlank()
    is EnrichmentRequest.ForTrack -> request.title.isBlank()
}
