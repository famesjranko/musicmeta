package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles per-entity enrichment logic for MusicBrainz.
 * Called by [MusicBrainzProvider] after routing by request/type.
 */
internal class MusicBrainzEnricher(
    private val api: MusicBrainzApi,
    private val providerId: String,
    private val minMatchScore: Int,
) {

    /** Cache artist lookups by MBID to avoid redundant API calls across types. */
    private val artistCache = ConcurrentHashMap<String, MusicBrainzArtist>()
    private val artistLookupMutex = Mutex()

    /** Lookup artist with rels (superset), caching to avoid redundant calls.
     *  BAND_MEMBERS, ARTIST_LINKS, and GENRE all need artist data for the same MBID. */
    private suspend fun cachedArtistLookup(mbid: String): MusicBrainzArtist? {
        artistCache[mbid]?.let { return it }
        return artistLookupMutex.withLock {
            artistCache[mbid]?.let { return@withLock it }
            val result = api.lookupArtistWithRels(mbid)
            result?.let { artistCache[mbid] = it }
            result
        }
    }

    internal suspend fun enrichAlbum(
        request: EnrichmentRequest.ForAlbum, type: EnrichmentType,
    ): EnrichmentResult {
        if (type in ARTIST_NEW_TYPES || type == EnrichmentType.CREDITS) {
            return EnrichmentResult.NotFound(type, providerId)
        }
        if (type == EnrichmentType.ALBUM_TRACKS) return enrichAlbumTracks(request)
        if (type == EnrichmentType.RELEASE_EDITIONS) return enrichAlbumEditions(request)
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = api.lookupRelease(mbid)
                ?: return EnrichmentResult.NotFound(type, providerId)
            return buildAlbumResult(full, type, ConfidenceCalculator.idBasedLookup())
        }
        val releases = api.searchReleases(request.title, request.artist)
        if (releases.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        val best = releases.firstOrNull { it.score >= minMatchScore }
            ?: return EnrichmentResult.NotFound(type, providerId)
        val needsLookup = type in RELATION_DEPENDENT_TYPES && best.tags.isEmpty() && best.label == null
        val resolved = if (needsLookup) api.lookupRelease(best.id) ?: best else best
        return buildAlbumResult(resolved, type, ConfidenceCalculator.searchScore(best.score))
    }

    internal suspend fun enrichAlbumTracks(
        request: EnrichmentRequest.ForAlbum,
    ): EnrichmentResult {
        val type = EnrichmentType.ALBUM_TRACKS
        val mbid = request.identifiers.musicBrainzId ?: run {
            api.searchReleases(request.title, request.artist)
                .firstOrNull { it.score >= minMatchScore }?.id
        } ?: return EnrichmentResult.NotFound(type, providerId)
        val release = api.lookupRelease(mbid)
            ?: return EnrichmentResult.NotFound(type, providerId)
        if (release.tracks.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type, data = MusicBrainzMapper.toTracklist(release.tracks),
            provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
            resolvedIdentifiers = MusicBrainzMapper.toAlbumIdentifiers(release),
        )
    }

    internal suspend fun enrichAlbumEditions(
        request: EnrichmentRequest.ForAlbum,
    ): EnrichmentResult {
        val type = EnrichmentType.RELEASE_EDITIONS
        val releaseGroupMbid = request.identifiers.musicBrainzReleaseGroupId
            ?: return EnrichmentResult.NotFound(type, providerId)
        val json = api.lookupReleaseGroup(releaseGroupMbid)
            ?: return EnrichmentResult.NotFound(type, providerId)
        val detail = MusicBrainzCreditParser.parseReleaseGroupDetail(json)
        if (detail.releases.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toReleaseEditions(detail),
            provider = providerId,
            confidence = ConfidenceCalculator.idBasedLookup(),
        )
    }

    internal suspend fun enrichArtist(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        // New types with specialized API calls
        if (type in ARTIST_NEW_TYPES) {
            return enrichArtistNewType(request, type)
        }

        // If we already have an MBID, skip search and use cached lookup
        val mbid = request.identifiers.musicBrainzId
        if (mbid != null) {
            val full = cachedArtistLookup(mbid)
                ?: return EnrichmentResult.NotFound(type, providerId)
            return buildArtistResult(full, type, ConfidenceCalculator.idBasedLookup())
        }

        val artists = api.searchArtists(request.name)
        if (artists.isEmpty()) return EnrichmentResult.NotFound(type, providerId)

        val best = pickBestArtist(request.name, artists)
        if (best.score < minMatchScore) {
            return EnrichmentResult.NotFound(type, providerId)
        }

        // Search results have metadata (genres, country) but lack URL relations
        // (wikidata, wikipedia). Do the full lookup when these are missing so
        // downstream providers (Wikidata, Wikipedia) can use them.
        val needsRelations = best.wikidataId == null && best.wikipediaTitle == null
        val resolved = if (needsRelations) {
            cachedArtistLookup(best.id) ?: best
        } else {
            best
        }

        return buildArtistResult(resolved, type, ConfidenceCalculator.searchScore(best.score))
    }

    internal suspend fun enrichArtistNewType(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult {
        val mbid = request.identifiers.musicBrainzId ?: run {
            val artists = api.searchArtists(request.name)
            val best = pickBestArtist(request.name, artists)
            if (best.score >= minMatchScore) best.id else null
        } ?: return EnrichmentResult.NotFound(type, providerId)

        return when (type) {
            EnrichmentType.BAND_MEMBERS -> {
                val artist = cachedArtistLookup(mbid)
                    ?: return EnrichmentResult.NotFound(type, providerId)
                val members = if (artist.bandMembers.isNotEmpty()) {
                    MusicBrainzMapper.toBandMembers(artist.bandMembers)
                } else if (artist.type == "Person") {
                    MusicBrainzMapper.toSoloArtistMember(artist)
                } else {
                    return EnrichmentResult.NotFound(type, providerId)
                }
                EnrichmentResult.Success(
                    type = type,
                    data = members,
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            EnrichmentType.ARTIST_DISCOGRAPHY -> {
                val groups = api.browseReleaseGroups(mbid)
                if (groups.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toDiscography(groups),
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                )
            }
            EnrichmentType.ARTIST_LINKS -> {
                val artist = cachedArtistLookup(mbid)
                    ?: return EnrichmentResult.NotFound(type, providerId)
                if (artist.urlRelations.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
                EnrichmentResult.Success(
                    type = type,
                    data = MusicBrainzMapper.toArtistLinks(artist.urlRelations),
                    provider = providerId, confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = MusicBrainzMapper.toArtistIdentifiers(artist),
                )
            }
            else -> EnrichmentResult.NotFound(type, providerId)
        }
    }

    internal suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (type == EnrichmentType.CREDITS) return enrichTrackCredits(request)

        val recordings = api.searchRecordings(request.title, request.artist)
        if (recordings.isEmpty()) return EnrichmentResult.NotFound(type, providerId)

        val best = recordings.firstOrNull { it.score >= minMatchScore }
            ?: return EnrichmentResult.NotFound(type, providerId)

        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toTrackMetadata(best),
            provider = providerId,
            confidence = ConfidenceCalculator.searchScore(best.score),
            resolvedIdentifiers = MusicBrainzMapper.toTrackIdentifiers(best),
        )
    }

    internal suspend fun enrichTrackCredits(
        request: EnrichmentRequest.ForTrack,
    ): EnrichmentResult {
        val type = EnrichmentType.CREDITS
        val mbid = request.identifiers.musicBrainzId
            ?: return EnrichmentResult.NotFound(type, providerId)
        val json = api.lookupRecording(mbid)
            ?: return EnrichmentResult.NotFound(type, providerId)
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)
        if (credits.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
        return EnrichmentResult.Success(
            type = type,
            data = MusicBrainzMapper.toCredits(credits),
            provider = providerId,
            confidence = ConfidenceCalculator.idBasedLookup(),
        )
    }

    private fun buildAlbumResult(
        release: MusicBrainzRelease,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult.Success = EnrichmentResult.Success(
        type = type,
        data = MusicBrainzMapper.toAlbumMetadata(release),
        provider = providerId,
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
        provider = providerId,
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
