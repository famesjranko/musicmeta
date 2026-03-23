package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Discogs enrichment provider. Searches for album releases to supply
 * cover art and label metadata, and artist endpoints for band members.
 * Requires a Discogs personal access token.
 */
class DiscogsProvider(
    private val tokenProvider: () -> String,
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    constructor(personalToken: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ personalToken }, httpClient, rateLimiter)

    private val api = DiscogsApi(tokenProvider, httpClient, rateLimiter)

    override val id = "discogs"
    override val displayName = "Discogs"
    override val requiresApiKey = true
    override val isAvailable: Boolean get() = tokenProvider().isNotBlank()

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ARTIST_PHOTO, priority = 40),
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 20),
        ProviderCapability(EnrichmentType.LABEL, priority = 50),
        ProviderCapability(EnrichmentType.RELEASE_TYPE, priority = 50),
        ProviderCapability(EnrichmentType.BAND_MEMBERS, priority = 50),
        ProviderCapability(EnrichmentType.ALBUM_METADATA, priority = 40),
        ProviderCapability(EnrichmentType.CREDITS, priority = 50),
        ProviderCapability(EnrichmentType.RELEASE_EDITIONS, priority = 50),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (!isAvailable) return EnrichmentResult.NotFound(type, id)

        if (type == EnrichmentType.ARTIST_PHOTO || type == EnrichmentType.BAND_MEMBERS) {
            val artistRequest = request as? EnrichmentRequest.ForArtist
                ?: return EnrichmentResult.NotFound(type, id)
            return enrichArtistType(artistRequest, type)
        }

        if (type == EnrichmentType.CREDITS) {
            val trackRequest = request as? EnrichmentRequest.ForTrack
                ?: return EnrichmentResult.NotFound(type, id)
            return try {
                enrichTrackCredits(trackRequest, type)
            } catch (e: Exception) {
                mapError(type, e)
            }
        }

        if (type == EnrichmentType.RELEASE_EDITIONS) {
            val albumRequest = request as? EnrichmentRequest.ForAlbum
                ?: return EnrichmentResult.NotFound(type, id)
            return try {
                enrichAlbumEditions(albumRequest, type)
            } catch (e: Exception) {
                mapError(type, e)
            }
        }

        val albumRequest = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(type, id)

        return try {
            // Discogs titles are "Artist - Title"; verify artist matches
            val releases = api.searchReleases(albumRequest.title, albumRequest.artist)
            val release = releases.firstOrNull {
                val discogsArtist = it.title.substringBefore(" - ").trim()
                ArtistMatcher.isMatch(albumRequest.artist, discogsArtist)
            } ?: releases.firstOrNull()
                ?: return EnrichmentResult.NotFound(type, id)
            if (type == EnrichmentType.ALBUM_METADATA) {
                enrichAlbumMetadataWithCommunity(release, albumRequest.identifiers)
            } else {
                enrichFromRelease(release, type)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichTrackCredits(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        val releaseIdStr = request.identifiers.get("discogsReleaseId")
            ?: return EnrichmentResult.NotFound(type, id)
        val releaseId = releaseIdStr.toLongOrNull()
            ?: return EnrichmentResult.NotFound(type, id)
        val detail = api.getReleaseDetails(releaseId)
            ?: return EnrichmentResult.NotFound(type, id)

        // First try track-level extraartists (filtered by matching title)
        val trackCredits = detail.tracklist
            .firstOrNull { it.title.equals(request.title, ignoreCase = true) }
            ?.extraartists
            .orEmpty()

        // Fall back to release-level extraartists if no track-specific credits
        val credits = trackCredits.ifEmpty { detail.extraartists }
        if (credits.isEmpty()) return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = DiscogsMapper.toCredits(credits),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = false),
        )
    }

    private suspend fun enrichAlbumEditions(
        request: EnrichmentRequest.ForAlbum,
        type: EnrichmentType,
    ): EnrichmentResult {
        val masterIdStr = request.identifiers.get("discogsMasterId")
            ?: return EnrichmentResult.NotFound(type, id)
        val masterId = masterIdStr.toLongOrNull()
            ?: return EnrichmentResult.NotFound(type, id)
        val versions = api.getMasterVersions(masterId)
        if (versions.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return EnrichmentResult.Success(
            type = type,
            data = DiscogsMapper.toReleaseEditions(versions),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = false),
        )
    }

    private suspend fun enrichAlbumMetadataWithCommunity(
        release: DiscogsRelease,
        identifiers: EnrichmentIdentifiers,
    ): EnrichmentResult {
        val baseMetadata = DiscogsMapper.toAlbumMetadata(release)
        // Fetch release details for community rating if a releaseId is available
        val releaseId = release.releaseId
            ?: identifiers.get("discogsReleaseId")?.toLongOrNull()
        val communityRating = if (releaseId != null) {
            api.getReleaseDetails(releaseId)?.communityRating
        } else null
        val metadata = if (communityRating != null) {
            baseMetadata.copy(communityRating = communityRating)
        } else baseMetadata
        return success(metadata, EnrichmentType.ALBUM_METADATA, release)
    }

    private suspend fun enrichArtistType(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val artistId = api.searchArtist(request.name)
                ?: return EnrichmentResult.NotFound(type, id)
            val artist = api.getArtist(artistId)
                ?: return EnrichmentResult.NotFound(type, id)
            when (type) {
                EnrichmentType.ARTIST_PHOTO -> {
                    val artwork = DiscogsMapper.toArtistPhoto(artist)
                        ?: return EnrichmentResult.NotFound(type, id)
                    success(artwork, type)
                }
                EnrichmentType.BAND_MEMBERS -> {
                    if (artist.members.isEmpty()) return EnrichmentResult.NotFound(type, id)
                    success(DiscogsMapper.toBandMembers(artist), type)
                }
                else -> EnrichmentResult.NotFound(type, id)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private fun enrichFromRelease(
        release: DiscogsRelease,
        type: EnrichmentType,
    ): EnrichmentResult {
        val data = when (type) {
            EnrichmentType.ALBUM_ART -> DiscogsMapper.toArtwork(release)
            EnrichmentType.LABEL -> DiscogsMapper.toLabelMetadata(release)
            EnrichmentType.RELEASE_TYPE -> DiscogsMapper.toReleaseTypeMetadata(release)
            EnrichmentType.ALBUM_METADATA -> DiscogsMapper.toAlbumMetadata(release)
            else -> null
        } ?: return EnrichmentResult.NotFound(type, id)
        return success(data, type, release)
    }

    private fun buildResolvedIdentifiers(release: DiscogsRelease): EnrichmentIdentifiers? {
        var ids = EnrichmentIdentifiers()
        if (release.releaseId != null) {
            ids = ids.withExtra("discogsReleaseId", release.releaseId.toString())
        }
        if (release.masterId != null) {
            ids = ids.withExtra("discogsMasterId", release.masterId.toString())
        }
        return if (ids.extra.isEmpty()) null else ids
    }

    private fun success(
        data: EnrichmentData,
        type: EnrichmentType,
        release: DiscogsRelease? = null,
    ) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = false),
        resolvedIdentifiers = release?.let { buildResolvedIdentifiers(it) },
    )
}
