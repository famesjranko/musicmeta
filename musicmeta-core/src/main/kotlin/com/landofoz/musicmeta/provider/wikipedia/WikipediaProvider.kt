package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
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
            requiresIdentifier = true,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        logger.debug(TAG, "wpTitle=${request.identifiers.wikipediaTitle}, wikidataId=${request.identifiers.wikidataId}")
        // Try direct Wikipedia title first, fall back to Wikidata sitelink resolution
        val title = request.identifiers.wikipediaTitle
            ?: resolveFromWikidata(request.identifiers.wikidataId)
        logger.debug(TAG, "Resolved title=$title")
        if (title == null) return EnrichmentResult.NotFound(type, id)

        val summary = api.getPageSummary(title)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Biography(
                text = summary.extract,
                source = "Wikipedia",
                thumbnailUrl = summary.thumbnailUrl,
            ),
            provider = id,
            confidence = CONFIDENCE,
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
        val json = wikidataRateLimiter.execute { httpClient.fetchJson(url) } ?: return null
        return json.optJSONObject("entities")
            ?.optJSONObject(wikidataId)
            ?.optJSONObject("sitelinks")
            ?.optJSONObject("enwiki")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "WikipediaProvider"
        /** Authoritative encyclopedia. High quality for notable artists. */
        const val CONFIDENCE = 0.95f
        const val WIKIDATA_API = "https://www.wikidata.org/w/api.php"
    }
}
