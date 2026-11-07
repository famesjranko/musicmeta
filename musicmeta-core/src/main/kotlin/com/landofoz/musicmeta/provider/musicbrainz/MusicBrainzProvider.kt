package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentLogger
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

    /**
     * MusicBrainz's disambiguation for each of [mbids], keyed by lowercased id, in one search.
     *
     * The engine's similar-artist labelling calls this after a merge. It is annotation, not
     * enrichment: an id MusicBrainz does not describe is absent from the map, and a failed request
     * is an empty one. See [MusicBrainzArtistEnrichment.describeArtists] for the memo and the
     * failure contract.
     */
    internal suspend fun describeArtists(mbids: List<String>): Map<String, String> =
        enricher().describeArtists(mbids)

    /** The probe behind [com.landofoz.musicmeta.EnrichmentEngine.discoverMbidEntityType], which holds its contract. */
    internal suspend fun discoverEntityType(mbid: String): MusicBrainzEntityType? =
        enricher().discoverEntityType(mbid)

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> = searchCandidates(request, limit, emptyList(), EnrichmentLogger.NoOp)

    internal suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
        artists: List<String>,
        logger: EnrichmentLogger,
    ): List<SearchCandidate> = MusicBrainzCandidateSearch(api, id, logger).search(request, limit, artists)

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
