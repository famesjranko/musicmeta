package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Enrichment provider backed by the MusicBrainz API.
 * Resolves identifiers and metadata for albums, artists, and tracks.
 */
class MusicBrainzProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
    private val minMatchScore: Int = DEFAULT_MIN_MATCH_SCORE,
    private val thumbnailSize: Int = DEFAULT_THUMBNAIL_SIZE,
) : EnrichmentProvider {

    override val id: String = "musicbrainz"

    private val api = MusicBrainzApi(httpClient, rateLimiter)
    private val enricher = MusicBrainzEnricher(api, id, minMatchScore)

    override val displayName: String = "MusicBrainz"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true
    override val isIdentityProvider: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        ProviderCapability(EnrichmentType.LABEL, priority = 100),
        ProviderCapability(EnrichmentType.RELEASE_DATE, priority = 100),
        ProviderCapability(EnrichmentType.RELEASE_TYPE, priority = 100),
        ProviderCapability(EnrichmentType.COUNTRY, priority = 100),
        ProviderCapability(EnrichmentType.BAND_MEMBERS, priority = 100),
        ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, priority = 100),
        ProviderCapability(EnrichmentType.ALBUM_TRACKS, priority = 100),
        ProviderCapability(EnrichmentType.ARTIST_LINKS, priority = 100),
        ProviderCapability(
            EnrichmentType.CREDITS,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID,
        ),
        ProviderCapability(
            EnrichmentType.RELEASE_EDITIONS,
            priority = 100,
            identifierRequirement = IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID,
        ),
    )

    override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult =
        enrich(request, EnrichmentType.GENRE)

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> =
        when (request) {
            is EnrichmentRequest.ForAlbum -> searchAlbumCandidates(request, limit)
            is EnrichmentRequest.ForArtist -> searchArtistCandidates(request, limit)
            is EnrichmentRequest.ForTrack -> emptyList()
        }

    private suspend fun searchAlbumCandidates(
        request: EnrichmentRequest.ForAlbum, limit: Int,
    ): List<SearchCandidate> {
        val releases = api.searchReleases(request.title, request.artist, limit)
            .ifEmpty { api.searchReleasesFuzzy(request.title, request.artist, limit) }
        return releases.map { release ->
            val thumb = if (release.hasFrontCover) {
                "https://coverartarchive.org/release/${release.id}/front-$thumbnailSize"
            } else null
            SearchCandidate(
                title = release.title, artist = release.artistCredit,
                year = release.date, country = release.country,
                releaseType = release.releaseType, score = release.score,
                thumbnailUrl = thumb, provider = id,
                identifiers = EnrichmentIdentifiers(
                    musicBrainzId = release.id,
                    musicBrainzReleaseGroupId = release.releaseGroupId,
                ),
                disambiguation = release.disambiguation,
            )
        }
    }

    private suspend fun searchArtistCandidates(
        request: EnrichmentRequest.ForArtist, limit: Int,
    ): List<SearchCandidate> {
        val artists = api.searchArtists(request.name, limit)
            .ifEmpty { api.searchArtistsFuzzy(request.name, limit) }
        return artists.map { artist ->
            SearchCandidate(
                title = artist.name, artist = null,
                year = artist.beginDate, country = artist.country,
                releaseType = artist.type, score = artist.score,
                thumbnailUrl = null, provider = id,
                identifiers = EnrichmentIdentifiers(musicBrainzId = artist.id),
                disambiguation = artist.disambiguation,
            )
        }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult =
        try {
            when (request) {
                is EnrichmentRequest.ForAlbum -> enricher.enrichAlbum(request, type)
                is EnrichmentRequest.ForArtist -> enricher.enrichArtist(request, type)
                is EnrichmentRequest.ForTrack -> enricher.enrichTrack(request, type)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }

    companion object {
        const val DEFAULT_MIN_MATCH_SCORE = 80
        const val DEFAULT_THUMBNAIL_SIZE = 250
    }
}
