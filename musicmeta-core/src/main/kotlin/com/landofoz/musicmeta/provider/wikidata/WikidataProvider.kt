package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.CallMemo
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext

/**
 * Provides artist photos, metadata and links from Wikidata properties.
 * P18 = image, P569 = birth date, P570 = death date,
 * P495 = country of origin, P106 = occupation, P856 = official website.
 * Every success also carries the external-id claims (P434 MusicBrainz, P1953 Discogs,
 * P1902 Spotify, P2850 Apple Music) as resolved identifiers, from the same single request.
 * Requires a wikidataId in the request identifiers.
 */
public class WikidataProvider internal constructor(
    private val api: WikidataApi,
    private val imageSize: Int,
    private val logger: EnrichmentLogger,
) : EnrichmentProvider {

    public constructor(
        httpClient: HttpClient,
        rateLimiter: RateLimiter,
        imageSize: Int = DEFAULT_IMAGE_SIZE,
    ) : this(WikidataApi(httpClient, rateLimiter), imageSize, EnrichmentLogger.NoOp)

    /**
     * As above, with somewhere to report a request this route has stopped serving.
     *
     * A second constructor rather than a fourth defaulted parameter on the first: a default would
     * move the existing constructor's JVM signature and break every Java caller of it. `internal`
     * because the engine's own builder is what passes a logger — a consumer registering the
     * provider by hand reaches the log through `EnrichmentEngine.Builder.logger` instead.
     */
    internal constructor(
        httpClient: HttpClient,
        rateLimiter: RateLimiter,
        imageSize: Int,
        logger: EnrichmentLogger,
    ) : this(WikidataApi(httpClient, rateLimiter), imageSize, logger)

    override val id: String = "wikidata"
    override val displayName: String = "Wikidata"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.ARTIST_PHOTO,
            priority = PRIORITY,
            identifierRequirement = IdentifierRequirement.WIKIDATA_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.COUNTRY,
            priority = 50,
            identifierRequirement = IdentifierRequirement.WIKIDATA_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.ARTIST_LINKS,
            priority = 50,
            identifierRequirement = IdentifierRequirement.WIKIDATA_ID,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val wikidataId = request.identifiers.wikidataId
        if (wikidataId.isNullOrBlank()) {
            return EnrichmentResult.NotFound(type, id)
        }

        val props = try {
            readClaims(wikidataId)
                ?: return EnrichmentResult.NotFound(type, id)
        } catch (e: Exception) {
            return mapError(type, e)
        }

        val resolvedIdentifiers = WikidataMapper.toIdentifiers(props)

        return when (type) {
            EnrichmentType.ARTIST_PHOTO -> {
                val imageUrl = props.imageUrl
                    ?: return EnrichmentResult.NotFound(type, id)
                EnrichmentResult.Success(
                    type,
                    WikidataMapper.toArtwork(imageUrl),
                    id,
                    ConfidenceCalculator.authoritative(),
                    resolvedIdentifiers = resolvedIdentifiers,
                )
            }
            EnrichmentType.COUNTRY -> {
                val metadata = WikidataMapper.toMetadata(props)
                if (metadata.country == null &&
                    metadata.beginDate == null &&
                    metadata.endDate == null &&
                    metadata.artistType == null
                ) {
                    return EnrichmentResult.NotFound(type, id)
                }
                EnrichmentResult.Success(
                    type,
                    metadata,
                    id,
                    ConfidenceCalculator.authoritative(),
                    resolvedIdentifiers = resolvedIdentifiers,
                )
            }
            EnrichmentType.ARTIST_LINKS -> {
                val website = props.officialWebsite
                    ?: return EnrichmentResult.NotFound(type, id)
                EnrichmentResult.Success(
                    type,
                    WikidataMapper.toArtistLinks(website),
                    id,
                    ConfidenceCalculator.authoritative(),
                    resolvedIdentifiers = resolvedIdentifiers,
                )
            }
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    /**
     * The entity's claims, or null when there are none to read. All three answerless outcomes leave
     * the caller with nothing, so none of them changes the result; only
     * [WikidataProperties.UnreadableShape] is logged, because it is the route having moved or
     * refused the request rather than anything about this artist.
     */
    private suspend fun readClaims(wikidataId: String): WikidataEntityProperties? =
        when (val properties = getEntityProperties(wikidataId)) {
            is WikidataProperties.Claims -> properties.value
            WikidataProperties.UnreadableShape -> {
                logger.debug(TAG, "Wikidata answered $wikidataId with no readable claims body")
                null
            }
            WikidataProperties.NoClaims, WikidataProperties.NoEntity -> null
        }

    /**
     * `getEntityProperties` for [wikidataId], memoized per [ProviderCallScope]/[CallMemo] (see
     * their KDocs) — ARTIST_PHOTO, COUNTRY and ARTIST_LINKS all read this same response.
     */
    private suspend fun getEntityProperties(wikidataId: String): WikidataProperties {
        val memo = currentCoroutineContext()[ProviderCallScope]
            ?.slot(this) { CallMemo<String, WikidataProperties>() }
            ?: return api.getEntityProperties(wikidataId, imageSize)
        return memo.get(wikidataId) { api.getEntityProperties(wikidataId, imageSize) }
    }

    public companion object {
        public const val DEFAULT_IMAGE_SIZE: Int = 1200
        private const val PRIORITY = 100
        private const val TAG = "WikidataProvider"
    }
}
