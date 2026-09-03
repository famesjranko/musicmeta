package com.landofoz.musicmeta.provider.coverartarchive

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
import kotlinx.coroutines.ensureActive

/**
 * Enrichment provider for album cover art from the Cover Art Archive.
 * Requires a MusicBrainz release or release-group ID.
 */
public class CoverArtArchiveProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
    private val artworkSize: Int = DEFAULT_ARTWORK_SIZE,
    private val thumbnailSize: Int = DEFAULT_THUMBNAIL_SIZE,
) : EnrichmentProvider {

    private val api = CoverArtArchiveApi(httpClient, rateLimiter)

    override val id: String = "coverartarchive"
    override val displayName: String = "Cover Art Archive"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.ALBUM_ART,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.ALBUM_ART_BACK,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.ALBUM_BOOKLET,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            type = EnrichmentType.CD_ART,
            priority = 50,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            // A ForTrack request's musicBrainzId names a recording, which 404s on CAA's release
            // endpoints, so it is never sent as releaseId for a track request — only the
            // release-group id can serve a track's art, via findArtwork's release-group fallback;
            // the three release-only capabilities have no such fallback, so a track request reaches
            // them with releaseId == null and returns NotFound before any HTTP call.
            val isTrackRequest = request is EnrichmentRequest.ForTrack
            val releaseId = request.identifiers.musicBrainzId.takeUnless { isTrackRequest }
            val groupId = request.identifiers.musicBrainzReleaseGroupId

            if (releaseId == null && groupId == null) {
                return EnrichmentResult.NotFound(type, id)
            }

            when (type) {
                EnrichmentType.ALBUM_ART -> findArtwork(releaseId, groupId, type)
                EnrichmentType.ALBUM_ART_BACK -> findImageByType(releaseId, type, "Back")
                EnrichmentType.ALBUM_BOOKLET -> findImageByType(releaseId, type, "Booklet")
                EnrichmentType.CD_ART -> findImageByType(releaseId, type, "Medium")
                else -> EnrichmentResult.NotFound(type, id)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun findArtwork(
        releaseId: String?,
        groupId: String?,
        type: EnrichmentType,
    ): EnrichmentResult {
        // Try release-specific art first
        if (releaseId != null) {
            val url = api.getArtworkUrl(releaseId, artworkSize) // primary — keeps throwing
            if (url != null) {
                val thumbUrl = degradeSideFetch { api.getArtworkUrl(releaseId, thumbnailSize) }
                val frontImage = degradeSideFetch { fetchFrontImage(releaseId) }
                return EnrichmentResult.Success(
                    type = type,
                    data = CoverArtArchiveMapper.toArtwork(url, thumbUrl, frontImage),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }
        }

        // Fall back to release-group art
        if (groupId != null) {
            val url = api.getGroupArtworkUrl(groupId, artworkSize) // primary — keeps throwing
            if (url != null) {
                val thumbUrl = degradeSideFetch { api.getGroupArtworkUrl(groupId, thumbnailSize) }
                return EnrichmentResult.Success(
                    type = type,
                    data = CoverArtArchiveMapper.toArtwork(url, thumbUrl),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }
        }

        return EnrichmentResult.NotFound(type, id)
    }

    /**
     * Best-effort: [block] is a supplementary CAA call (the thumbnail-size redirect, or
     * [fetchFrontImage]'s metadata lookup) made *after* the primary front-cover url is already
     * resolved. A transient here must not turn an achievable ALBUM_ART [EnrichmentResult.Success]
     * into an [EnrichmentResult.Error] — that would also open the circuit breaker against a healthy
     * Cover Art Archive for a failure the primary lookup never had (docs/pitfalls.md §4), and it
     * would discard a url the caller already has in hand. Mirrors
     * [com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzEnricher.resolveReleaseGroupWikiLinks].
     */
    // SwallowedException: intentional — see the KDoc above. This provider has no logger to hand the
    // exception to; degrading silently is the fix, not an oversight (detekt cannot tell them apart).
    @Suppress("SwallowedException")
    private suspend fun <T> degradeSideFetch(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }

    /** Find an image by its CAA type (e.g., "Back", "Booklet") from metadata. */
    private suspend fun findImageByType(
        releaseId: String?,
        type: EnrichmentType,
        imageType: String,
    ): EnrichmentResult {
        if (releaseId == null) return EnrichmentResult.NotFound(type, id)
        val images = getArtworkMetadata(releaseId)
            ?: return EnrichmentResult.NotFound(type, id)
        val image = images.firstOrNull { imageType in it.types }
            ?: return EnrichmentResult.NotFound(type, id)
        return EnrichmentResult.Success(
            type = type,
            // "small" is a deprecated upstream alias for "250"; prefer the canonical key.
            data = CoverArtArchiveMapper.toArtwork(
                image.url, image.thumbnails["250"] ?: image.thumbnails["small"], image,
            ),
            provider = id,
            confidence = ConfidenceCalculator.idBasedLookup(),
        )
    }

    /** Fetch image metadata for sizes. Returns the first front image, or null. */
    private suspend fun fetchFrontImage(releaseId: String): CoverArtArchiveImage? {
        val images = getArtworkMetadata(releaseId) ?: return null
        return images.firstOrNull { it.front }
    }

    /**
     * `getArtworkMetadata` for [releaseId], memoized for the life of one [ProviderCallScope] —
     * [findImageByType]'s three metadata branches and [fetchFrontImage]'s ALBUM_ART side-fetch all
     * read this same response. Release-id-keyed rather than one slot per call: a call resolving
     * more than one release must not share one release's answer with another's. A miss is memoized
     * too, so a release with no artwork costs one request instead of one per type; called outside
     * an engine, there is no scope to memoize in and every call hits upstream.
     *
     * A *failure* is shared on the same terms: the first reader's exhausted attempt is rethrown to
     * the other three rather than each running its own attempts against an endpoint already known
     * to be failing, so one call costs one attempt budget for this release and not one per type.
     * Under a sustained failure every type reports the error it would have earned alone; the cost of
     * sharing is that a transient recovering *between* two types no longer reaches the second, which
     * now inherits the first's error instead of finding its own success.
     *
     * Wrapping the outcome in a [Result] is what lets [CallMemo] — which holds only what
     * [CallMemo.get]'s fetch *returns* — hold a failure at all. `ensureActive()` decides which
     * failures are eligible (`docs/pitfalls.md` §2): only *this* job's own cancellation escapes the
     * memo, leaving nothing behind for a sibling type to inherit. A cancellation raised by the
     * upstream call itself — a consumer [HttpClient] reporting a hung endpoint through its own
     * `withTimeout` — is that endpoint failing, and is held like any other failure; it is also the
     * shape the amplification this memo exists to stop takes in practice.
     */
    private suspend fun getArtworkMetadata(releaseId: String): List<CoverArtArchiveImage>? {
        val memo = currentCoroutineContext()[ProviderCallScope]
            ?.slot(this) { CallMemo<String, Result<List<CoverArtArchiveImage>?>>() }
            ?: return api.getArtworkMetadata(releaseId)
        return memo.get(releaseId) {
            try {
                Result.success(api.getArtworkMetadata(releaseId))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
        }.getOrThrow()
    }

    public companion object {
        public const val DEFAULT_ARTWORK_SIZE: Int = 1200
        public const val DEFAULT_THUMBNAIL_SIZE: Int = 250
    }
}
