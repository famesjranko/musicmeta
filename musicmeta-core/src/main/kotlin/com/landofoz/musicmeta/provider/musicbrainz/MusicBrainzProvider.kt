package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext

/**
 * Enrichment provider backed by the MusicBrainz API.
 * Resolves identifiers and metadata for albums, artists, and tracks.
 */
public class MusicBrainzProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
    private val minMatchScore: Int = DEFAULT_MIN_MATCH_SCORE,
) : EnrichmentProvider {

    override val id: String = "musicbrainz"

    private val api = MusicBrainzApi(httpClient, rateLimiter)

    /**
     * One enricher per call, never one per provider: it memoizes the lookups a request's types
     * repeat, and that memo must not outlive the call that filled it ([ProviderCallScope]). Inside
     * an engine, every type of one `enrich()` shares this call's enricher — which is the whole
     * saving; called directly, each call gets its own.
     */
    private suspend fun enricher(): MusicBrainzEnricher =
        currentCoroutineContext()[ProviderCallScope]?.slot(this, ::newEnricher) ?: newEnricher()

    private fun newEnricher() = MusicBrainzEnricher(api, id, minMatchScore)

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
        ProviderCapability(EnrichmentType.TRACK_METADATA, priority = 100),
        // Popularity at a low priority: MusicBrainz contributes a community rating, not the counts
        // Last.fm and ListenBrainz measure, so it should never lead the merged flat fields.
        ProviderCapability(EnrichmentType.ARTIST_POPULARITY, priority = 20),
        ProviderCapability(EnrichmentType.TRACK_POPULARITY, priority = 20),
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

    /** The probe behind [com.landofoz.musicmeta.discoverMbidEntityType], which holds its contract. */
    internal suspend fun discoverEntityType(mbid: String): MusicBrainzEntityType? =
        enricher().discoverEntityType(mbid)

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> =
        when (request) {
            is EnrichmentRequest.ForAlbum -> searchAlbumCandidates(request, limit)
            is EnrichmentRequest.ForArtist -> searchArtistCandidates(request, limit)
            is EnrichmentRequest.ForTrack -> searchTrackCandidates(request, limit)
        }

    /**
     * Every candidate built here has a null [SearchCandidate.thumbnailUrl]: a release search
     * response carries nothing that tells real cover art from a Cover Art Archive 404, and asking
     * per candidate would cost one rate-limited lookup each.
     */
    private suspend fun searchAlbumCandidates(
        request: EnrichmentRequest.ForAlbum, limit: Int,
    ): List<SearchCandidate> {
        val releases = api.searchReleases(request.title, request.artist, limit)
            .ifEmpty { api.searchReleasesFuzzy(request.title, request.artist, limit) }
        return releases.map { release ->
            SearchCandidate(
                title = release.title, artist = release.artistCredit,
                year = release.date, country = release.country,
                releaseType = release.releaseType, score = release.score,
                thumbnailUrl = null, provider = id,
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

    private suspend fun searchTrackCandidates(
        request: EnrichmentRequest.ForTrack, limit: Int,
    ): List<SearchCandidate> {
        val recordings = api.searchRecordings(request.title, request.artist, request.album, limit)
            .ifEmpty { api.searchRecordingsFuzzy(request.title, request.artist, limit) }
        return recordings.map { recording ->
            // year/country/releaseType/thumbnailUrl are null — a recording search hit carries
            // none of its own (see MusicBrainzEnricher's MusicBrainzRecording.toCandidate).
            SearchCandidate(
                title = recording.title, artist = recording.artistCredit,
                year = null, country = null,
                releaseType = null, score = recording.score,
                thumbnailUrl = null, provider = id,
                identifiers = EnrichmentIdentifiers(
                    musicBrainzId = recording.id,
                    musicBrainzReleaseGroupId = recording.artReleaseGroupId,
                ),
                disambiguation = recording.disambiguation,
            )
        }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult =
        try {
            val enricher = enricher()
            when (request) {
                is EnrichmentRequest.ForAlbum -> enricher.enrichAlbum(request, type)
                is EnrichmentRequest.ForArtist -> enricher.enrichArtist(request, type)
                is EnrichmentRequest.ForTrack -> enricher.enrichTrack(request, type)
            }
        } catch (e: Exception) {
            mapError(type, e)
        }

    public companion object {
        public const val DEFAULT_MIN_MATCH_SCORE: Int = 80
    }
}
