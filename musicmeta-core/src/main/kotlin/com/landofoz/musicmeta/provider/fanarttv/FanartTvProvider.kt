package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement.MUSICBRAINZ_ID
import com.landofoz.musicmeta.IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.CallMemo
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext

/**
 * Fanart.tv enrichment provider. Supplies high-quality artist images
 * (photos, backgrounds, logos). Requires a MusicBrainz ID in the request
 * identifiers and a Fanart.tv project API key.
 */
public class FanartTvProvider(
    private val projectKeyProvider: () -> String,
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    public constructor(projectKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ projectKey }, httpClient, rateLimiter)

    private val api = FanartTvApi(projectKeyProvider, httpClient, rateLimiter)

    /**
     * `getArtistImages` for [mbid], memoized for the life of one [ProviderCallScope] — all four
     * artist-image types (photo/background/logo/banner) read this same response. MBID-keyed rather
     * than one slot per call: a call resolving more than one artist must not share one artist's
     * answer with another's. A miss is memoized too, so an unknown artist costs one request instead
     * of one per type; called outside an engine, there is no scope to memoize in and every call
     * hits upstream.
     */
    private suspend fun getArtistImages(mbid: String): FanartTvArtistImages? {
        val memos = currentCoroutineContext()[ProviderCallScope]?.slot(this, ::FanartTvMemos)
            ?: return api.getArtistImages(mbid)
        return memos.artistImages.get(mbid) { api.getArtistImages(mbid) }
    }

    /**
     * `getAlbumImages` for a release-group id, memoized for the life of one [ProviderCallScope] —
     * ALBUM_ART and CD_ART both read this same response. Release-group-id-keyed rather than one
     * slot per call, and memoized on a miss too, for the same reasons [getArtistImages] is.
     */
    private suspend fun getAlbumImages(releaseGroupMbid: String): FanartTvAlbumImages? {
        val memos = currentCoroutineContext()[ProviderCallScope]?.slot(this, ::FanartTvMemos)
            ?: return api.getAlbumImages(releaseGroupMbid)
        return memos.albumImages.get(releaseGroupMbid) { api.getAlbumImages(releaseGroupMbid) }
    }

    /**
     * This call's Fanart.tv memos — one [ProviderCallScope] slot holding both documents this
     * provider fetches, since a slot belongs to one owner and this owner has two.
     */
    private class FanartTvMemos {
        val artistImages = CallMemo<String, FanartTvArtistImages?>()
        val albumImages = CallMemo<String, FanartTvAlbumImages?>()
    }

    override val id: String = "fanarttv"
    override val displayName: String = "Fanart.tv"
    override val requiresApiKey: Boolean = true
    override val isAvailable: Boolean get() = projectKeyProvider().isNotBlank()

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(EnrichmentType.ARTIST_PHOTO, priority = 80, identifierRequirement = MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.ARTIST_BACKGROUND, priority = 100, identifierRequirement = MUSICBRAINZ_ID),
        ProviderCapability(EnrichmentType.ARTIST_LOGO, priority = 100, identifierRequirement = MUSICBRAINZ_ID),
        ProviderCapability(
            EnrichmentType.ALBUM_ART,
            priority = 30,
            identifierRequirement = MUSICBRAINZ_RELEASE_GROUP_ID,
        ),
        ProviderCapability(
            EnrichmentType.CD_ART,
            priority = 100,
            identifierRequirement = MUSICBRAINZ_RELEASE_GROUP_ID,
        ),
        ProviderCapability(EnrichmentType.ARTIST_BANNER, priority = 100, identifierRequirement = MUSICBRAINZ_ID),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (!isAvailable) return EnrichmentResult.NotFound(type, id)

        return try {
            // Album-scoped types resolve only through the album endpoint, which is keyed by
            // release group id. There is no artist-endpoint fallback: an artist document merges
            // albumcover/cdart across every album, so it can only return another album's art.
            if (type == EnrichmentType.ALBUM_ART || type == EnrichmentType.CD_ART) {
                val releaseGroupMbid = request.identifiers.musicBrainzReleaseGroupId
                    ?: return EnrichmentResult.NotFound(type, id)
                enrichAlbumArt(releaseGroupMbid, type)
            } else {
                if (request !is EnrichmentRequest.ForArtist) {
                    return EnrichmentResult.NotFound(type, id)
                }
                val mbid = request.identifiers.musicBrainzId
                    ?: return EnrichmentResult.NotFound(type, id)
                val images = getArtistImages(mbid)
                    ?: return EnrichmentResult.NotFound(type, id)
                enrichFromImages(images, type)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    /** Fetches album art for a release group from the Fanart.tv album-specific endpoint. */
    private suspend fun enrichAlbumArt(
        releaseGroupMbid: String,
        type: EnrichmentType,
    ): EnrichmentResult {
        val albumImages = getAlbumImages(releaseGroupMbid)
            ?: return EnrichmentResult.NotFound(type, id)
        val imageList = when (type) {
            EnrichmentType.CD_ART -> albumImages.cdArt
            else -> albumImages.albumCovers
        }
        val image = imageList.mostLiked() ?: return EnrichmentResult.NotFound(type, id)
        return success(FanartTvMapper.toArtwork(image, imageList), type)
    }

    private fun enrichFromImages(
        images: FanartTvArtistImages,
        type: EnrichmentType,
    ): EnrichmentResult {
        val imageList = when (type) {
            EnrichmentType.ARTIST_PHOTO -> images.thumbnails
            EnrichmentType.ARTIST_BACKGROUND -> images.backgrounds
            EnrichmentType.ARTIST_LOGO -> images.logos
            EnrichmentType.ARTIST_BANNER -> images.banners
            else -> null
        } ?: return EnrichmentResult.NotFound(type, id)
        val image = imageList.mostLiked() ?: return EnrichmentResult.NotFound(type, id)
        return success(FanartTvMapper.toArtwork(image, imageList), type)
    }

    // fanart.tv's community likes are its own quality signal, not guaranteed best-first ordering.
    // maxByOrNull keeps the first maximum on ties — same convention as bestArtistMatch /
    // pickBestRecording — so list order still breaks ties deterministically.
    private fun List<FanartTvImage>.mostLiked(): FanartTvImage? = maxByOrNull { it.likes }

    private fun success(data: EnrichmentData, type: EnrichmentType) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = ConfidenceCalculator.authoritative(),
    )
}
