package com.landofoz.musicmeta.provider.wikipedia

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
import com.landofoz.musicmeta.provider.wikidata.EnwikiSitelink
import com.landofoz.musicmeta.provider.wikidata.WikidataApi
import kotlinx.coroutines.currentCoroutineContext

/**
 * Provides artist biographies, album descriptions and photos from Wikipedia articles.
 *
 * `ALBUM_DESCRIPTION` reuses the same title-resolution and `Biography` mapping as `ARTIST_BIO`;
 * only the identifiers differ — for an album request they come from the release-group's Wikidata
 * relation (`MusicBrainzAlbumEnrichment.buildAlbumResult`), not the artist's.
 *
 * **English only.** Every request goes to `en.wikipedia.org`, so the resolved title must be an
 * English article title. Title resolution, in order:
 * 1. `EnrichmentIdentifiers.wikipediaTitle` — set from a MusicBrainz `wikipedia` URL relation, and
 *    only ever from an `en.wikipedia.org` one; other-language relations are ignored upstream.
 * 2. The `enwiki` sitelink of `EnrichmentIdentifiers.wikidataId`, fetched with `sitefilter=enwiki`
 *    so no other language can be returned.
 *
 * There is no other-language fallback: if neither yields an English title the result is `NotFound`.
 * A non-English article is never used, because its text would not be a usable English bio.
 */
public class WikipediaProvider internal constructor(
    private val api: WikipediaApi,
    private val wikidataApi: WikidataApi,
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
) : EnrichmentProvider {

    /**
     * Constructs the provider from HTTP infrastructure. Both api clients are internal
     * implementation details, so this is the public entry point consumers use to register the
     * provider with the engine.
     *
     * [wikidataRateLimiter] throttles the *Wikidata* host, not a second Wikipedia one: this
     * provider reaches two hosts and a limiter is per host. Pass the same instance
     * `WikidataProvider` was given, as `withDefaultProviders()` does — one host behind two
     * independent limiters is served at twice the agreed rate.
     */
    public constructor(
        httpClient: HttpClient,
        rateLimiter: RateLimiter,
        wikidataRateLimiter: RateLimiter = RateLimiter(100),
        logger: EnrichmentLogger = EnrichmentLogger.NoOp,
    ) : this(WikipediaApi(httpClient, rateLimiter), WikidataApi(httpClient, wikidataRateLimiter), logger)

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
        ProviderCapability(
            type = EnrichmentType.ALBUM_DESCRIPTION,
            priority = 100,
            identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        logger.debug(TAG, "wpTitle=${request.identifiers.wikipediaTitle}, wikidataId=${request.identifiers.wikidataId}")
        val title = request.identifiers.wikipediaTitle ?: try {
            resolveFromWikidata(request.identifiers.wikidataId)
        } catch (e: Exception) {
            return mapError(type, e)
        }
        logger.debug(TAG, "Resolved title=$title")
        if (title == null) return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.ARTIST_BIO, EnrichmentType.ALBUM_DESCRIPTION -> enrichBio(title, type)
            EnrichmentType.ARTIST_PHOTO -> enrichArtistPhoto(title, type)
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichBio(title: String, type: EnrichmentType): EnrichmentResult {
        val summary = try {
            api.getPageExtract(title) ?: return EnrichmentResult.NotFound(type, id)
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
        // The list arrives lead-image first; where the article flags none, the head is its first
        // surviving image in article order.
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
     * Resolve Wikipedia article title from Wikidata entity sitelinks, memoized per
     * [ProviderCallScope]/[CallMemo] (see their KDocs) — ARTIST_BIO and ARTIST_PHOTO both resolve
     * through this when the request carries no `wikipediaTitle`.
     *
     * Many artists have a Wikidata entry but no direct Wikipedia URL relation in MusicBrainz.
     */
    private suspend fun resolveFromWikidata(wikidataId: String?): String? {
        if (wikidataId.isNullOrBlank()) return null
        val memo = currentCoroutineContext()[ProviderCallScope]
            ?.slot(this) { CallMemo<String, String?>() }
            ?: return fetchWikidataTitle(wikidataId)
        return memo.get(wikidataId) { fetchWikidataTitle(wikidataId) }
    }

    /**
     * All three title-less outcomes mean the same thing to a caller — no English article — so none
     * of them changes the answer. Only [EnwikiSitelink.UnreadableShape] is logged, because it is
     * the route having moved rather than anything about this artist.
     */
    private suspend fun fetchWikidataTitle(wikidataId: String): String? =
        when (val sitelink = wikidataApi.getEnwikiSitelink(wikidataId)) {
            is EnwikiSitelink.Title -> sitelink.value
            EnwikiSitelink.UnreadableShape -> {
                logger.debug(TAG, "Wikidata answered $wikidataId with no sitelinks object")
                null
            }
            EnwikiSitelink.NoArticle, EnwikiSitelink.NoEntity -> null
        }

    private companion object {
        private const val TAG = "WikipediaProvider"
    }
}
