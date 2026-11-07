package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.SearchTitleMatcher
import com.landofoz.musicmeta.engine.displayKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Candidate discovery only; enrichment keeps its own identity-resolution policy. */
internal class MusicBrainzCandidateSearch(
    private val api: MusicBrainzApi,
    private val id: String,
    private val logger: EnrichmentLogger,
) {
    suspend fun search(request: EnrichmentRequest, limit: Int, artists: List<String>): List<SearchCandidate> {
        if (limit <= 0) return emptyList()
        val pool = when (request) {
            is EnrichmentRequest.ForAlbum -> searchAlbumCandidates(request, limit)
            is EnrichmentRequest.ForArtist -> return searchArtistCandidates(request, limit)
            is EnrichmentRequest.ForTrack -> searchTrackCandidates(request, limit)
        }
        val preferred = artists.firstOrNull()
        val extra = if (preferred == null) emptyList() else preferredPool(request, preferred, pool, limit)
        val (title, suppliedArtist) = when (request) {
            is EnrichmentRequest.ForAlbum -> request.title to request.artist
            is EnrichmentRequest.ForTrack -> request.title to request.artist
            is EnrichmentRequest.ForArtist -> request.name to ""
        }
        return (pool + extra)
            .groupBy { it.value.identifiers.musicBrainzId }.values
            .map { entries -> entries.maxBy { it.value.matchScore } }
            .sortedWith(
                compareByDescending<Candidate> { SearchTitleMatcher.equivalent(title, it.value.title) }
                    .thenByDescending { sameArtist(suppliedArtist, it) }
                    .thenByDescending {
                        if (SearchTitleMatcher.equivalent(title, it.value.title)) 0f else it.value.matchScore
                    }
                    .thenBy { artistRank(artists, it) }
                    .thenByDescending { it.value.disambiguation.isNullOrBlank() }
                    .thenByDescending { it.officialAlbumRecording }
                    .thenByDescending { it.value.matchScore }
                    .thenBy { it.value.artist.orEmpty().lowercase() }
                    .thenBy { it.value.title.lowercase() }
                    .thenBy { it.value.identifiers.musicBrainzId },
            ).let { candidates ->
                MusicBrainzAlbumCandidateOrder.order(candidates, title) { it.release }
            }.map { it.value }.distinctBy { it.displayKey() }.take(limit)
    }

    private fun artistRank(artists: List<String>, candidate: Candidate): Int =
        artists.indexOfFirst { sameArtist(it, candidate) }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    private fun sameArtist(expected: String, candidate: Candidate): Boolean =
        ArtistMatcher.matchQuality(expected, candidate.value.artist.orEmpty()) >= ArtistMatcher.QUALITY_SAME_NAME ||
            candidate.artistCredits.any {
                ArtistMatcher.matchQuality(expected, it) >= ArtistMatcher.QUALITY_SAME_NAME
            }

    private suspend fun preferredPool(
        request: EnrichmentRequest, artist: String, pool: List<Candidate>, limit: Int,
    ): List<Candidate> = try {
        when (request) {
            is EnrichmentRequest.ForTrack ->
                api.searchCanonicalRecordings(request.title, artist, request.album).map { it.toCandidate() }
            is EnrichmentRequest.ForAlbum -> if (pool.any {
                    artistRank(listOf(artist), it) == 0 &&
                        SearchTitleMatcher.equivalent(request.title, it.value.title) &&
                        it.value.disambiguation.isNullOrBlank()
                }) {
                emptyList()
            } else searchAlbumCandidates(request.copy(artist = artist), limit)
            is EnrichmentRequest.ForArtist -> emptyList()
        }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("MusicBrainzSearch", "Preferred artist candidate search failed: ${e.message}", e)
        emptyList()
    }

    /**
     * Every candidate built here has a null [SearchCandidate.thumbnailUrl]: a release search
     * response carries nothing that tells real cover art from a Cover Art Archive 404, and asking
     * per candidate would cost one rate-limited lookup each.
     */
    private suspend fun searchAlbumCandidates(
        request: EnrichmentRequest.ForAlbum, limit: Int,
    ): List<Candidate> {
        val releases = api.searchReleases(request.title, request.artist, MusicBrainzApi.CANONICAL_SEARCH_LIMIT)
            .ifEmpty { api.searchReleasesFuzzy(request.title, request.artist, limit) }
        return releases.map { release ->
            Candidate(
                value = SearchCandidate(
                    title = release.title, artist = release.artistCredit,
                    year = release.date?.take(4)?.toIntOrNull(), country = release.country,
                    releaseType = release.releaseType,
                    matchScore = ConfidenceCalculator.searchScore(release.score),
                    thumbnailUrl = null, provider = id,
                    identifiers = EnrichmentIdentifiers(
                        musicBrainzId = release.id,
                        musicBrainzReleaseGroupId = release.releaseGroupId,
                    ),
                    disambiguation = release.disambiguation,
                ),
                artistCredits = release.artistCredits,
                release = release,
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
                year = artist.beginDate?.take(4)?.toIntOrNull(), country = artist.country,
                releaseType = artist.type,
                matchScore = ConfidenceCalculator.searchScore(artist.score),
                thumbnailUrl = null, provider = id,
                identifiers = EnrichmentIdentifiers(musicBrainzId = artist.id),
                disambiguation = artist.disambiguation,
            )
        }.distinctBy { it.displayKey() }.take(limit)
    }

    private suspend fun searchTrackCandidates(
        request: EnrichmentRequest.ForTrack, limit: Int,
    ): List<Candidate> {
        val pool = api.searchCandidateRecordingPool(request.title, request.artist, request.album)
        val strict = pool.recordings
        val recordings = strict.ifEmpty { api.searchRecordingsFuzzy(request.title, request.artist, limit) }
        val canonical = if (request.artist.isNotBlank() && strict.isNotEmpty() && !pool.albumMatched) {
            try {
                api.searchCandidateCanonicalRecordings(request.title, request.artist, request.album)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("MusicBrainzSearch", "Canonical candidate search failed: ${e.message}", e)
                emptyList()
            }
        } else emptyList()
        return (recordings + canonical).map { it.toCandidate() }
    }

    private fun MusicBrainzRecording.toCandidate(): Candidate {
        // A recording search hit carries no year, country, release type, or thumbnail of its own.
        return Candidate(
            value = SearchCandidate(
                title = title, artist = artistCredit,
                year = null, country = null,
                releaseType = null,
                matchScore = ConfidenceCalculator.searchScore(score),
                thumbnailUrl = null, provider = this@MusicBrainzCandidateSearch.id,
                identifiers = EnrichmentIdentifiers(
                    musicBrainzId = id,
                    musicBrainzReleaseGroupId = artReleaseGroupId,
                ),
                disambiguation = disambiguation,
            ),
            artistCredits = artistCredits,
            officialAlbumRecording = hasOfficialAlbumRelease,
        )
    }

    /** Ranking evidence stays internal; the display credit is never split into guessed names. */
    private data class Candidate(
        val value: SearchCandidate,
        val artistCredits: List<String>,
        val officialAlbumRecording: Boolean = false,
        val release: MusicBrainzRelease? = null,
    )
}
