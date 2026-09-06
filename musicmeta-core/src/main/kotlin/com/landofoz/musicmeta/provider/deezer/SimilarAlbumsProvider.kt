package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarAlbum
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.NameMatchTier
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Discovers albums similar to a seed album by fetching Deezer related artists
 * and sampling their discographies. Scores results by artist similarity rank
 * and era proximity to the seed album's release year.
 *
 * **This is an artist-derived approximation, not album-level similarity.** Deezer's public
 * API has no album-similarity resource — `/album/{id}/related`, `/similar`, `/radio` and
 * `/recommendations` all return `InvalidQueryException` (code 600), and the `/album/{id}`
 * payload carries no similarity field. `/artist/{id}/related` is the only similarity signal
 * on offer, so results are "albums by artists similar to the *artist*", weighted by era.
 * Nothing is dropped for its era — [eraMultiplier] only re-ranks.
 *
 * The consequence a consumer must plan for: the seed album's title identifies the *artist* and
 * nothing more (see [resolveSeedArtist]). The only album-level input to the ranking is
 * [EnrichmentRequest.ForAlbum.year], which feeds [eraMultiplier] — so two albums by the same artist
 * return a near-identical list, and an identical one when the caller passes no `year`. Present it
 * as "if you like this artist", not "albums like this record".
 *
 * Standalone provider (not composite): all Deezer API calls happen here,
 * not inside a synthesizer.
 */
public class SimilarAlbumsProvider internal constructor(
    private val api: DeezerApi,
) : EnrichmentProvider {

    /**
     * Constructs the provider from HTTP infrastructure. [DeezerApi] is an internal
     * implementation detail, so this is the public entry point consumers use to
     * register the provider with the engine.
     */
    public constructor(
        httpClient: HttpClient,
        rateLimiter: RateLimiter = RateLimiter(100),
    ) : this(DeezerApi(httpClient, rateLimiter))

    override val id: String = "deezer-similar-albums"
    override val displayName: String = "Deezer Similar Albums"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    /**
     * `SIMILAR_ALBUMS` is derived from `/artist/{id}/related`, not from any album-similarity
     * endpoint — see the class KDoc for what that costs a consumer. No
     * `identifierRequirement`: the artist is resolved from the album search when `deezerId` is
     * absent ([resolveSeedArtist]).
     */
    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ALBUMS, priority = 100),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (type != EnrichmentType.SIMILAR_ALBUMS) return EnrichmentResult.NotFound(type, id)
        return try {
            enrichSimilarAlbums(request)
        } catch (e: Exception) {
            // mapError, not a hand-rolled copy of it: it owns the ErrorKind classification, and
            // keeping every provider on it means that classification changes in one place.
            // Cancellation is not decided here — mapError cannot tell ours from a provider's own
            // withTimeout, so ProviderChain's ensureActive() settles it before the breaker. (#53)
            mapError(type, e)
        }
    }

    private suspend fun enrichSimilarAlbums(request: EnrichmentRequest): EnrichmentResult {
        val albumRequest = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        val seedArtist = resolveSeedArtist(albumRequest)
            ?: return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        // Fetch up to 5 related artists
        val relatedArtists = api.getRelatedArtists(seedArtist.id, limit = 5)
        if (relatedArtists.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        val seedYear = albumRequest.year
        val count = relatedArtists.size.coerceAtLeast(1)

        // For each related artist, fetch up to 3 albums and score them
        val albums = mutableListOf<SimilarAlbum>()
        for ((index, artist) in relatedArtists.withIndex()) {
            val artistScore = 1.0f - (index.toFloat() / count) * 0.9f
            val artistAlbums = api.getArtistAlbums(artist.id, limit = 3)
            for (album in artistAlbums) {
                val eraMultiplier = eraMultiplier(seedYear, album.releaseDate?.take(4)?.toIntOrNull())
                val finalScore = artistScore * eraMultiplier
                albums.add(DeezerMapper.toSimilarAlbum(album, artist.name, finalScore))
            }
        }

        if (albums.isEmpty()) return EnrichmentResult.NotFound(EnrichmentType.SIMILAR_ALBUMS, id)

        // Deduplicate by title+artist (case-insensitive), sort by score desc, cap at 20
        val deduped = albums
            .groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .map { (_, dupes) -> dupes.maxByOrNull { it.artistMatchScore } ?: dupes.first() }
            .sortedByDescending { it.artistMatchScore }
            .take(20)

        return EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ALBUMS,
            data = EnrichmentData.SimilarAlbums(deduped),
            provider = id,
            // 0.8 scores the *lookup*, not the strength of the recommendation. Note it overstates
            // on the caller-supplied `deezerId` branch, which trusts that id and verifies no name.
            // Deriving from related artists is a property of the type here (there is no
            // album-level source to be more confident than), so that caveat belongs in the KDoc
            // above, not smuggled into a number consumers rank providers by.
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true) *
                seedArtist.nameTier.confidenceFactor,
            resolvedIdentifiers = EnrichmentIdentifiers()
                .with(IdentifierNamespace.DEEZER, seedArtist.id.toString()),
        )
    }

    /** The Deezer artist `/artist/{id}/related` is walked from, and how sure we are it is the right one. */
    private data class SeedArtist(val id: Long, val nameTier: NameMatchTier)

    /**
     * The artist whose neighbours become this list, or null when no evidence names one.
     *
     * A name search cannot separate two acts called the same thing: `bestArtistMatchOrAlias` breaks
     * an exact-name tie on `nb_fan`, so the whole list would follow whichever homonym is more
     * popular, at a confidence that reports the name as an exact match. Nor can the alias pool
     * settle it — a pool of name forms widens the accept set, and both acts already match on the
     * name itself.
     *
     * So the ladder is evidence first, in the order the evidence identifies an artist:
     *
     * 1. A Deezer id on the request. The caller asserted it; nothing here checks it.
     * 2. The album search's own `artist.id`, the seam
     *    [DeezerProvider.enrichSimilarTracks] takes off the track search. The requested *title* is
     *    what only one of the same-named acts recorded, so the hit identifies the artist even where
     *    the name does not. Selection is [selectAlbum]'s, so a remaster suffix is tolerated and a
     *    live or deluxe edition of a bare request is not.
     * 3. The name search, but only when its pool held no second candidate under the requested name
     *    ([DeezerArtistSearchResult.ambiguousName]).
     *
     * An ambiguous name that no album hit resolves therefore yields `NotFound`: a list of albums by
     * the wrong act's neighbours is not a thinner answer than the right one, it is a different act's
     * answer, and a consumer has nothing on the result to tell it so.
     */
    private suspend fun resolveSeedArtist(request: EnrichmentRequest.ForAlbum): SeedArtist? {
        request.identifiers.get(IdentifierNamespace.DEEZER)?.toLongOrNull()
            ?.let { return SeedArtist(it, NameMatchTier.CANONICAL) }

        val albumMatch = api.searchAlbums("${request.artist} ${request.title}", ALBUM_SEARCH_LIMIT)
            .selectAlbum(request)
        albumMatch?.candidate?.artistId?.let { return SeedArtist(it, albumMatch.nameTier) }

        val byName = api.searchArtist(request.artist) ?: return null
        return if (byName.ambiguousName) null else SeedArtist(byName.id, byName.nameTier)
    }

    /**
     * Returns an era proximity multiplier based on how close the album year is
     * to the seed album year. Returns 1.0 when seedYear is null (no era data).
     *
     * Within ±5 years: 1.2x (close era boost)
     * Within ±10 years: 1.0x (neutral)
     * Beyond ±10 years: 0.8x (era penalty)
     */
    private fun eraMultiplier(seedYear: Int?, albumYear: Int?): Float {
        if (seedYear == null || albumYear == null) return 1.0f
        val diff = kotlin.math.abs(seedYear - albumYear)
        return when {
            diff <= 5 -> 1.2f
            diff <= 10 -> 1.0f
            else -> 0.8f
        }
    }
}

/**
 * Candidate pool for the seed's album search — enough hits for the requested edition to surface.
 * File-private rather than a companion constant: a `const val` in a private companion of a public
 * class is still a public static field, and moves a line in the published API dump.
 */
private const val ALBUM_SEARCH_LIMIT = 5
