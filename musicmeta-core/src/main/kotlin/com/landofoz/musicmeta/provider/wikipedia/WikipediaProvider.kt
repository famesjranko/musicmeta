package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Provides artist biographies from Wikipedia page summaries.
 * Resolves the Wikipedia title from either:
 * 1. Direct wikipediaTitle in identifiers (from MusicBrainz URL relations)
 * 2. Wikidata sitelinks (when only wikidataId is available)
 */
class WikipediaProvider(
    private val httpClient: HttpClient,
    rateLimiter: RateLimiter,
    private val wikidataRateLimiter: RateLimiter = RateLimiter(100),
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
) : EnrichmentProvider {

    private val api = WikipediaApi(httpClient, rateLimiter)

    override val id: String = "wikipedia"
    override val displayName: String = "Wikipedia"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.ARTIST_BIO,
            priority = 100,
            identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE,
        ),
        ProviderCapability(
            type = EnrichmentType.ARTIST_PHOTO,
            priority = 30,
            identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        logger.debug(TAG, "wpTitle=${request.identifiers.wikipediaTitle}, wikidataId=${request.identifiers.wikidataId}")
        val title = request.identifiers.wikipediaTitle
            ?: resolveFromWikidata(request.identifiers.wikidataId)
        logger.debug(TAG, "Resolved title=$title")
        if (title == null) return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.ARTIST_BIO -> enrichBio(title, type)
            EnrichmentType.ARTIST_PHOTO -> enrichArtistPhoto(title, type)
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichBio(title: String, type: EnrichmentType): EnrichmentResult {
        val summary = try {
            api.getPageSummary(title) ?: return EnrichmentResult.NotFound(type, id)
        } catch (e: Exception) {
            return mapError(type, e)
        }
        return EnrichmentResult.Success(
            type = type,
            data = WikipediaMapper.toBiography(summary),
            provider = id,
            confidence = ConfidenceCalculator.authoritative(),
        )
    }

    private suspend fun enrichArtistPhoto(title: String, type: EnrichmentType): EnrichmentResult {
        val mediaItems = try {
            api.getPageMediaList(title)
        } catch (e: Exception) {
            return mapError(type, e)
        }
        val bestImage = mediaItems.firstOrNull()
            ?: return EnrichmentResult.NotFound(type, id)
        return EnrichmentResult.Success(
            type = type,
            data = WikipediaMapper.toArtwork(bestImage),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = false),
        )
    }

    /**
     * Resolve Wikipedia article title from Wikidata entity sitelinks.
     * Many artists have a Wikidata entry but no direct Wikipedia URL relation in MusicBrainz.
     */
    private suspend fun resolveFromWikidata(wikidataId: String?): String? {
        if (wikidataId.isNullOrBlank()) return null
        val url = "$WIKIDATA_API?action=wbgetentities&ids=$wikidataId" +
            "&props=sitelinks&sitefilter=enwiki&format=json"
        val json = wikidataRateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return json.optJSONObject("entities")
            ?.optJSONObject(wikidataId)
            ?.optJSONObject("sitelinks")
            ?.optJSONObject("enwiki")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "WikipediaProvider"
        const val WIKIDATA_API = "https://www.wikidata.org/w/api.php"
    }
}
