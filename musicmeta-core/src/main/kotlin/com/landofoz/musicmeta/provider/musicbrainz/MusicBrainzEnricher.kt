package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Store under an access-ordered cap, evicting least-recently-used until it fits.
 * Only correct on an access-ordered [LinkedHashMap], and only under that map's mutex — every read
 * of such a map is a write.
 */
private fun <V> MutableMap<String, V>.putCapped(key: String, value: V, cap: Int) {
    put(key, value)
    while (size > cap) remove(keys.first())
}

/**
 * Handles per-entity enrichment logic for MusicBrainz.
 * Called by [MusicBrainzProvider] after routing by request/type.
 */
internal class MusicBrainzEnricher(
    private val api: MusicBrainzApi,
    private val providerId: String,
    private val minMatchScore: Int,
) {

    /**
     * Cache artist lookups by MBID to avoid redundant API calls across types.
     * Access-ordered and capped: one enricher lives as long as the engine does, so an
     * unbounded map would grow with every distinct artist a long-lived process ever sees.
     * Access order means every read mutates it, so all access is under [artistLookupMutex].
     */
    private val artistCache = LinkedHashMap<String, MusicBrainzArtist>(ARTIST_CACHE_MAX_ENTRIES, 0.75f, true)
    private val artistLookupMutex = Mutex()

    /**
     * Cache release lookups by MBID, same shape and cap as [artistCache].
     * One album is looked up more than once per `enrich()`: GENRE resolves it as identity and again
     * in the fan-out the identity MBID enables, and ALBUM_TRACKS wants the same response a third
     * time. Each repeat is a ~1.1s wait on the shared MusicBrainz limiter.
     * Access order means every read mutates it, so all access is under [releaseLookupMutex].
     */
    private val releaseCache = LinkedHashMap<String, MusicBrainzRelease>(RELEASE_CACHE_MAX_ENTRIES, 0.75f, true)
    private val releaseLookupMutex = Mutex()

    private suspend fun cachedReleaseLookup(mbid: String): MusicBrainzRelease? =
        releaseLookupMutex.withLock {
            releaseCache[mbid]?.let { return@withLock it }
            api.lookupRelease(mbid)?.also { releaseCache.putCapped(mbid, it, RELEASE_CACHE_MAX_ENTRIES) }
        }

    /** Lookup artist with rels (superset), caching to avoid redundant calls.
     *  BAND_MEMBERS, ARTIST_LINKS, and GENRE all need artist data for the same MBID. */
    private suspend fun cachedArtistLookup(mbid: String): MusicBrainzArtist? =
        artistLookupMutex.withLock {
            artistCache[mbid]?.let { return@withLock it }
            api.lookupArtistWithRels(mbid)?.also { artistCache.putCapped(mbid, it, ARTIST_CACHE_MAX_ENTRIES) }
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
            val full = cachedReleaseLookup(mbid)
                ?: return EnrichmentResult.NotFound(type, providerId)
            return buildAlbumResult(full, type, ConfidenceCalculator.idBasedLookup())
        }
        val releases = api.searchReleases(request.title, request.artist)
        if (releases.isEmpty()) {
            val fuzzy = api.searchReleasesFuzzy(request.title, request.artist)
            return EnrichmentResult.NotFound(type, providerId,
                suggestions = fuzzy.takeIf { it.isNotEmpty() }?.take(MAX_SUGGESTIONS)?.map { it.toCandidate() })
        }
        val best = releases.firstOrNull { it.score >= minMatchScore }
            ?: return EnrichmentResult.NotFound(type, providerId,
                suggestions = releases.take(MAX_SUGGESTIONS).map { it.toCandidate() })
        // A search hit carries tags only when its release group happens to have them; the release
        // lookup is what fills them. GENRE is the one type that reads them and the one this path
        // reaches — LABEL is answered from the identity payload and never gets here.
        val resolved = if (type == EnrichmentType.GENRE && best.tags.isEmpty()) {
            cachedReleaseLookup(best.id) ?: best
        } else {
            best
        }
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
        val release = cachedReleaseLookup(mbid)
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
        if (artists.isEmpty()) {
            val fuzzy = api.searchArtistsFuzzy(request.name)
            return EnrichmentResult.NotFound(type, providerId,
                suggestions = fuzzy.takeIf { it.isNotEmpty() }?.take(MAX_SUGGESTIONS)?.map { it.toCandidate() })
        }

        val best = pickBestArtist(request.name, artists)
        if (best.score < minMatchScore) {
            return EnrichmentResult.NotFound(type, providerId,
                suggestions = artists.take(MAX_SUGGESTIONS).map { it.toCandidate() })
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

        val recordings = api.searchRecordings(request.title, request.artist, request.album)
        if (recordings.isEmpty()) return EnrichmentResult.NotFound(type, providerId)

        val best = pickBestRecording(request.title, recordings)
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

    /**
     * Rank the recording pool above [minMatchScore] instead of taking `firstOrNull` — MB search
     * ties score-100 hits and puts demo/live/cover takes ahead of the studio original
     * (`docs/pitfalls.md` §7). Score stays the floor: everything below [minMatchScore] is out
     * before ranking starts. Among survivors, highest tier first, keeping pool order among ties
     * (`maxWithOrNull` keeps the first maximum, same convention as [pickBestArtist]'s sibling in
     * `DeezerApi.rankTracks`):
     *
     * 1. exact (case-insensitive) title match against [title] — verified live: a per-member
     *    cover/karaoke recording titled e.g. "Enter Sandman (Ulrich)" can carry a clean
     *    disambiguation and still beat the studio original on tiers 2/3 alone, so title has to be
     *    checked first, same tier order as `DeezerApi.rankTracks`'s tier 1
     * 2. no unrequested disambiguation marker (demo/live/remix/remaster) — separates "Enter
     *    Sandman" from "Enter Sandman" disambiguated "demo: 1990-08-13"
     * 3. carries an Official release on an Album release-group
     *    ([MusicBrainzRecording.hasOfficialAlbumRelease]) — prefers the studio album cut over a
     *    single/compilation-only recording when neither carries a disambiguation marker
     */
    private fun pickBestRecording(title: String, recordings: List<MusicBrainzRecording>): MusicBrainzRecording? =
        recordings.filter { it.score >= minMatchScore }
            .map { it to it.recordingRank(title) }
            .maxWithOrNull(
                compareBy({ it.second.exactTitle }, { it.second.cleanDisambiguation }, { it.second.officialAlbum }),
            )
            ?.first

    private fun MusicBrainzRecording.recordingRank(title: String) = RecordingRank(
        exactTitle = this.title.trim().equals(title.trim(), ignoreCase = true),
        cleanDisambiguation = !hasUnrequestedMarker(disambiguation),
        officialAlbum = hasOfficialAlbumRelease,
    )

    private data class RecordingRank(
        val exactTitle: Boolean,
        val cleanDisambiguation: Boolean,
        val officialAlbum: Boolean,
    )

    /** True when [disambiguation] carries a demo/live/remix/remaster marker. */
    private fun hasUnrequestedMarker(disambiguation: String?): Boolean {
        if (disambiguation.isNullOrBlank()) return false
        val lower = disambiguation.lowercase()
        return UNREQUESTED_DISAMBIGUATION_MARKERS.any { lower.contains(it) }
    }

    private fun MusicBrainzRelease.toCandidate() = SearchCandidate(
        title = title, artist = artistCredit, year = date,
        country = country, releaseType = releaseType, score = score,
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id, musicBrainzReleaseGroupId = releaseGroupId),
        disambiguation = disambiguation,
    )

    private fun MusicBrainzArtist.toCandidate() = SearchCandidate(
        title = name, artist = null, year = beginDate,
        country = country, releaseType = type, score = score,
        thumbnailUrl = null, provider = providerId,
        identifiers = EnrichmentIdentifiers(musicBrainzId = id),
        disambiguation = disambiguation,
    )

    companion object {
        private const val MAX_SUGGESTIONS = 3

        /** Cap on [artistCache], matching InMemoryEnrichmentCache's default. */
        internal const val ARTIST_CACHE_MAX_ENTRIES = 500

        /** Cap on [releaseCache]. Same default, counted separately — an album run fills both. */
        internal const val RELEASE_CACHE_MAX_ENTRIES = 500

        /** New artist types routed through enrichArtistNewType(). */
        private val ARTIST_NEW_TYPES = setOf(
            EnrichmentType.BAND_MEMBERS,
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentType.ARTIST_LINKS,
        )

        /** Disambiguation markers that make a recording a worse identity match — see [pickBestRecording]. */
        private val UNREQUESTED_DISAMBIGUATION_MARKERS = listOf("demo", "live", "remix", "remaster")
    }
}
