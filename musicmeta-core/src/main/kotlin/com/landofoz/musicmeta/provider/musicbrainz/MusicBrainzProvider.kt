package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ConfidenceCalculator
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

    private val api = MusicBrainzApi(httpClient, rateLimiter)

    override val id: String = "musicbrainz"
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
    ): List<SearchCandidate> =
        api.searchReleases(request.title, request.artist, limit).map { release ->
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
            )
        }

    private suspend fun searchArtistCandidates(
        request: EnrichmentRequest.ForArtist, limit: Int,
    ): List<SearchCandidate> =
        api.searchArtists(request.name, limit).map { artist ->
            SearchCandidate(
                title = artist.name, artist = null,
                year = artist.beginDate, country = artist.country,
                releaseType = artist.type, score = artist.score,
                thumbnailUrl = null, provider = id,
                identifiers = EnrichmentIdentifiers(musicBrainzId = artist.id),
            )
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
        request: EnrichmentRequest.ForAlbum, type: EnrichmentType,
    ): EnrichmentResult {
        if (type == EnrichmentType.ALBUM_TRACKS) return enrichAlbumTracks(request)
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = api.lookupRelease(mbid) ?: return EnrichmentResult.NotFound(type, id)
            return buildAlbumResult(full, type, ConfidenceCalculator.idBasedLookup())
        }
        val releases = api.searchReleases(request.title, request.artist)
        if (releases.isEmpty()) return EnrichmentResult.NotFound(type, id)
        val best = releases.firstOrNull { it.score >= minMatchScore }
            ?: return EnrichmentResult.NotFound(type, id)
        val needsLookup = type in RELATION_DEPENDENT_TYPES && best.tags.isEmpty() && best.label == null
        val resolved = if (needsLookup) api.lookupRelease(best.id) ?: best else best
        return buildAlbumResult(resolved, type, ConfidenceCalculator.searchScore(best.score))
    }

    private suspend fun enrichAlbumTracks(request: EnrichmentRequest.ForAlbum): EnrichmentResult {
        val type = EnrichmentType.ALBUM_TRACKS
        val mbid = request.identifiers.musicBrainzId ?: run {
            api.searchReleases(request.title, request.artist)
                .firstOrNull { it.score >= minMatchScore }?.id
        } ?: return EnrichmentResult.NotFound(type, id)
        val release = api.lookupRelease(mbid) ?: return EnrichmentResult.NotFound(type, id)
        if (release.tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
        return EnrichmentResult.Success(
            type = type, data = MusicBrainzMapper.toTracklist(release.tracks),
            provider = id, confidence = ConfidenceCalculator.idBasedLookup(),
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release),
        )
    }

    private suspend fun enrichArtist(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        // New types with specialized API calls
        if (type in ARTIST_NEW_TYPES) {
            return enrichArtistNewType(request, type)
        }

        // If we already have an MBID, skip search and go straight to lookup
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = api.lookupArtist(mbid)
                ?: return EnrichmentResult.NotFound(type, id)
            return buildArtistResult(full, type, ConfidenceCalculator.idBasedLookup())
        }

        val artists = api.searchArtists(request.name)
        if (artists.isEmpty()) return EnrichmentResult.NotFound(type, id)

        val best = pickBestArtist(request.name, artists)
        if (best.score < minMatchScore) {
            return EnrichmentResult.NotFound(type, id)
        }

        // Search results have metadata (genres, country) but lack URL relations
        // (wikidata, wikipedia). Do the full lookup when these are missing so
        // downstream providers (Wikidata, Wikipedia) can use them.
        val needsRelations = best.wikidataId == null && best.wikipediaTitle == null
        val resolved = if (needsRelations) {
            api.lookupArtist(best.id) ?: best
        } else {
            best
        }

        return buildArtistResult(resolved, type, ConfidenceCalculator.searchScore(best.score))
    }

    private suspend fun enrichArtistNewType(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val mbid = request.identifiers.musicBrainzId ?: run {
            val artists = api.searchArtists(request.name)
            val best = pickBestArtist(request.name, artists)
            if (best.score >= minMatchScore) best.id else null
        } ?: return EnrichmentResult.NotFound(type, id)

        return when (type) {
            EnrichmentType.BAND_MEMBERS -> {
                val artist = api.lookupArtistWithRels(mbid)
                    ?: return EnrichmentResult.NotFound(type, id)
                if (artist.bandMembers.isEmpty()) return EnrichmentResult.NotFound(type, id)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toBandMembers(artist.bandMembers),
                    provider = id, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            EnrichmentType.ARTIST_DISCOGRAPHY -> {
                val groups = api.browseReleaseGroups(mbid)
                if (groups.isEmpty()) return EnrichmentResult.NotFound(type, id)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toDiscography(groups),
                    provider = id, confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }
            EnrichmentType.ARTIST_LINKS -> {
                val artist = api.lookupArtist(mbid)
                    ?: return EnrichmentResult.NotFound(type, id)
                if (artist.urlRelations.isEmpty()) return EnrichmentResult.NotFound(type, id)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toArtistLinks(artist.urlRelations),
                    provider = id, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        val recordings = api.searchRecordings(request.title, request.artist)
        if (recordings.isEmpty()) return EnrichmentResult.NotFound(type, id)

        val best = recordings.firstOrNull { it.score >= minMatchScore }
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toTrackMetadata(best),
            provider = id,
            confidence = ConfidenceCalculator.searchScore(best.score),
            resolvedIdentifiers = MusicBrainzMapper.toTrackIdentifiers(best),
        )
    }

    private fun buildAlbumResult(
        release: MusicBrainzRelease,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult.Success = EnrichmentResult.Success(
        type = type,
        data = MusicBrainzMapper.toAlbumMetadata(release),
        provider = id,
        confidence = confidence,
        resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release),
    )

    private fun buildArtistResult(
        artist: MusicBrainzArtist,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult.Success = EnrichmentResult.Success(
        type = type,
        data = MusicBrainzMapper.toArtistMetadata(artist),
        provider = id,
        confidence = confidence,
        resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
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
        const val DEFAULT_MIN_MATCH_SCORE = 80
        const val DEFAULT_THUMBNAIL_SIZE = 250
        /** Types that require a full lookup (not just search) to get URL relations. */
        private val RELATION_DEPENDENT_TYPES = setOf(
            EnrichmentType.ARTIST_PHOTO,
            EnrichmentType.ARTIST_BIO,
            EnrichmentType.ARTIST_BACKGROUND,
            EnrichmentType.ARTIST_LOGO,
        )
        /** New artist types routed through enrichArtistNewType(). */
        private val ARTIST_NEW_TYPES = setOf(
            EnrichmentType.BAND_MEMBERS,
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentType.ARTIST_LINKS,
        )
    }
}
