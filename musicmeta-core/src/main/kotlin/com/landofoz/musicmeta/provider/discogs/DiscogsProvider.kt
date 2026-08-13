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
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * This call's [DiscogsAlbumScope], shared by ALBUM_ART, LABEL, RELEASE_TYPE and ALBUM_METADATA
     * so no two of them search or select an album independently in one `enrich()` fan-out
     * ([ProviderCallScope], `docs/pitfalls.md` §12). Called directly (no engine context), each call
     * gets its own.
     */
    private suspend fun albumScope(): DiscogsAlbumScope =
        currentCoroutineContext()[ProviderCallScope]?.slot(this) { DiscogsAlbumScope(api) } ?: DiscogsAlbumScope(api)

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
            // Discogs titles are "Artist - Title"; accept and rank on both halves, shared by every
            // type this call asks for so they select one release, not one search-ranked pick each.
            val release = albumScope().resolveRelease(albumRequest)?.release
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
            degradeSideFetch { api.getReleaseDetails(releaseId)?.communityRating }
        } else null
        val metadata = if (communityRating != null) {
            baseMetadata.copy(communityRating = communityRating)
        } else baseMetadata
        // Only reached with a release the album search verified against the requested artist.
        return success(metadata, EnrichmentType.ALBUM_METADATA, release, hasArtistMatch = true)
    }

    /**
     * Best-effort: [block] is a supplementary Discogs call (the release-detail fetch for
     * `communityRating`) made *after* the primary answer for the type is already resolved. A
     * transient here must not turn an achievable [EnrichmentResult.Success] into an
     * [EnrichmentResult.Error] — that would also open the circuit breaker against a healthy Discogs
     * for a failure the primary lookup never had (docs/pitfalls.md §4), and it would discard
     * `baseMetadata` the caller already has in hand. Mirrors
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

    private suspend fun enrichArtistType(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        return try {
            val artistId = api.searchArtist(request.name)
                ?: return EnrichmentResult.NotFound(type, id)
            val artist = api.getArtist(artistId)
                ?: return EnrichmentResult.NotFound(type, id)
            // searchArtist already name-matches the pool; this re-checks the *detail* record,
            // whose `name` is a different field from the search result's `title`.
            if (!ArtistMatcher.isMatch(request.name, artist.name)) {
                return EnrichmentResult.NotFound(type, id)
            }
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
        // Only reached with a release the album search verified against the requested artist.
        return success(data, type, release, hasArtistMatch = true)
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
        // Defaults false: only the album search matches on an artist name.
        hasArtistMatch: Boolean = false,
    ) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = id,
        confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch),
        resolvedIdentifiers = release?.let { buildResolvedIdentifiers(it) },
    )
}

/**
 * One `enrich()` call's release-search selection, held only long enough to serve ALBUM_ART, LABEL,
 * RELEASE_TYPE and ALBUM_METADATA from one search and one accepted release instead of one
 * independently-ranked search per type — see [DiscogsProvider.albumScope]. Dies with the call
 * ([ProviderCallScope]), so a mis-resolved artist/title never outlives a `forceRefresh`.
 */
private class DiscogsAlbumScope(private val api: DiscogsApi) {

    private val mutex = Mutex()
    private val releases = mutableMapOf<String, DiscogsAlbumChoice?>()

    /**
     * The accepted-and-ranked search hit for [request], with its selection evidence, one search per
     * distinct complete selection input per call. Keyed on every field selection reads, including
     * `year`, so a request differing only in `year` cannot reuse a stale selection.
     */
    suspend fun resolveRelease(request: EnrichmentRequest.ForAlbum): DiscogsAlbumChoice? {
        val key = "${request.artist}|${request.title}|${request.year}"
        return mutex.withLock {
            if (releases.containsKey(key)) {
                releases.getValue(key)
            } else {
                val pool = api.searchReleases(request.title, request.artist)
                val match = pool.selectRelease(request)
                releases[key] = match
                match
            }
        }
    }
}
