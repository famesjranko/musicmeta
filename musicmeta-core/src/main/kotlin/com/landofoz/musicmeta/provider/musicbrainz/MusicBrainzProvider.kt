package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
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
) : EnrichmentProvider {

    private val api = MusicBrainzApi(httpClient, rateLimiter)

    override val id: String = "musicbrainz"
    override val displayName: String = "MusicBrainz"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        ProviderCapability(EnrichmentType.LABEL, priority = 100),
        ProviderCapability(EnrichmentType.RELEASE_DATE, priority = 100),
        ProviderCapability(EnrichmentType.RELEASE_TYPE, priority = 100),
        ProviderCapability(EnrichmentType.COUNTRY, priority = 100),
    )

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
        request: EnrichmentRequest.ForAlbum,
        limit: Int,
    ): List<SearchCandidate> {
        val releases = api.searchReleases(request.title, request.artist, limit)
        return releases.map { release ->
            SearchCandidate(
                title = release.title,
                artist = release.artistCredit,
                year = release.date,
                country = release.country,
                releaseType = release.releaseType,
                score = release.score,
                thumbnailUrl = if (release.hasFrontCover) {
                    "https://coverartarchive.org/release/${release.id}/front-250"
                } else null,
                identifiers = EnrichmentIdentifiers(
                    musicBrainzId = release.id,
                    musicBrainzReleaseGroupId = release.releaseGroupId,
                ),
                provider = id,
            )
        }
    }

    private suspend fun searchArtistCandidates(
        request: EnrichmentRequest.ForArtist,
        limit: Int,
    ): List<SearchCandidate> {
        val artists = api.searchArtists(request.name, limit)
        return artists.map { artist ->
            SearchCandidate(
                title = artist.name,
                artist = null,
                year = artist.beginDate,
                country = artist.country,
                releaseType = artist.type,
                score = artist.score,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(
                    musicBrainzId = artist.id,
                ),
                provider = id,
            )
        }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult =
        try {
            when (request) {
                is EnrichmentRequest.ForAlbum -> enrichAlbum(request, type)
                is EnrichmentRequest.ForArtist -> enrichArtist(request, type)
                is EnrichmentRequest.ForTrack -> enrichTrack(request, type)
            }
        } catch (e: Exception) {
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }

    private suspend fun enrichAlbum(
        request: EnrichmentRequest.ForAlbum,
        type: EnrichmentType,
    ): EnrichmentResult {
        // If we already have an MBID, skip search and go straight to lookup
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = api.lookupRelease(mbid)
                ?: return EnrichmentResult.NotFound(type, id)
            return EnrichmentResult.Success(
                type = type,
                data = buildAlbumResolution(full),
                provider = id,
                confidence = 1.0f,
            )
        }

        val releases = api.searchReleases(request.title, request.artist)
        if (releases.isEmpty()) return EnrichmentResult.RateLimited(type, id)

        val best = releases.firstOrNull { it.score >= MIN_MATCH_SCORE }
            ?: return EnrichmentResult.NotFound(type, id)

        // Search already includes release-group tags, label-info, and cover-art-archive.
        // Only do a lookup if the type needs data that search doesn't provide.
        val needsLookup = type in RELATION_DEPENDENT_TYPES &&
            best.tags.isEmpty() && best.label == null
        val resolved = if (needsLookup) {
            api.lookupRelease(best.id) ?: best
        } else {
            best
        }

        return EnrichmentResult.Success(
            type = type,
            data = buildAlbumResolution(resolved),
            provider = id,
            confidence = best.score / 100f,
        )
    }

    private suspend fun enrichArtist(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        // If we already have an MBID, skip search and go straight to lookup
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = api.lookupArtist(mbid)
                ?: return EnrichmentResult.NotFound(type, id)
            return EnrichmentResult.Success(
                type = type,
                data = buildArtistResolution(full),
                provider = id,
                confidence = 1.0f,
            )
        }

        val artists = api.searchArtists(request.name)
        if (artists.isEmpty()) return EnrichmentResult.RateLimited(type, id)

        val best = pickBestArtist(request.name, artists)
        if (best.score < MIN_MATCH_SCORE) {
            return EnrichmentResult.NotFound(type, id)
        }

        // Search results have metadata (genres, country) but lack URL relations
        // (wikidata, wikipedia). Only do the expensive lookup when the requested
        // type actually needs those relations — saves ~1.1s per item in bulk mode.
        val needsRelations = type in RELATION_DEPENDENT_TYPES &&
            best.wikidataId == null && best.wikipediaTitle == null
        val resolved = if (needsRelations) {
            api.lookupArtist(best.id) ?: best
        } else {
            best
        }

        return EnrichmentResult.Success(
            type = type,
            data = buildArtistResolution(resolved),
            provider = id,
            confidence = best.score / 100f,
        )
    }

    private suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        val recordings = api.searchRecordings(request.title, request.artist)
        if (recordings.isEmpty()) return EnrichmentResult.RateLimited(type, id)

        val best = recordings.firstOrNull { it.score >= MIN_MATCH_SCORE }
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.IdentifierResolution(
                musicBrainzId = best.id,
                score = best.score,
                metadata = EnrichmentData.Metadata(
                    genres = best.tags.takeIf { it.isNotEmpty() },
                    isrc = best.isrcs.firstOrNull(),
                ),
            ),
            provider = id,
            confidence = best.score / 100f,
        )
    }

    private fun buildAlbumResolution(release: MusicBrainzRelease): EnrichmentData.IdentifierResolution =
        EnrichmentData.IdentifierResolution(
            musicBrainzId = release.id,
            musicBrainzReleaseGroupId = release.releaseGroupId,
            score = release.score,
            hasFrontCover = release.hasFrontCover,
            metadata = EnrichmentData.Metadata(
                genres = release.tags.takeIf { it.isNotEmpty() },
                label = release.label,
                releaseDate = release.date,
                releaseType = release.releaseType,
                country = release.country,
                barcode = release.barcode,
                disambiguation = release.disambiguation,
            ),
        )

    private fun buildArtistResolution(artist: MusicBrainzArtist): EnrichmentData.IdentifierResolution =
        EnrichmentData.IdentifierResolution(
            musicBrainzId = artist.id,
            wikidataId = artist.wikidataId,
            wikipediaTitle = artist.wikipediaTitle,
            score = artist.score,
            metadata = EnrichmentData.Metadata(
                genres = artist.tags.takeIf { it.isNotEmpty() },
                country = artist.country,
                disambiguation = artist.disambiguation,
                artistType = artist.type,
                beginDate = artist.beginDate,
                endDate = artist.endDate,
            ),
        )

    /**
     * Rank artist candidates: exact name match with tags > exact name match >
     * has tags with high score > highest score.
     */
    private fun pickBestArtist(
        query: String,
        candidates: List<MusicBrainzArtist>,
    ): MusicBrainzArtist = candidates.sortedByDescending { artist ->
        val exactMatch = artist.name.equals(query, ignoreCase = true)
        val hasTags = artist.tags.isNotEmpty()
        when {
            exactMatch && hasTags -> 3
            exactMatch -> 2
            hasTags -> 1
            else -> 0
        }
    }.first()

    companion object {
        const val MIN_MATCH_SCORE = 80
        /** Types that require a full lookup (not just search) to get URL relations. */
        private val RELATION_DEPENDENT_TYPES = setOf(
            EnrichmentType.ARTIST_PHOTO,
            EnrichmentType.ARTIST_BIO,
            EnrichmentType.ARTIST_BACKGROUND,
            EnrichmentType.ARTIST_LOGO,
        )
    }
}
