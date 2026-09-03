package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.AlbumMatch
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.trustedProviderIdentifier
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enrichment provider using Apple's iTunes Search and Lookup APIs.
 * Provides album art, album metadata, album tracks, and artist discography.
 * No API key needed.
 *
 * Artwork URL trick: iTunes returns 100x100 thumbnails, but replacing
 * "100x100bb" with "1200x1200bb" gives high-resolution images.
 */
public class ITunesProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter = RateLimiter(3000),
    private val artworkSize: Int = DEFAULT_ARTWORK_SIZE,
) : EnrichmentProvider {

    private val api = ITunesApi(httpClient, rateLimiter)

    /**
     * This call's `lookup?upc=` candidates, shared across every type asked of the same barcode —
     * ALBUM_METADATA, an art type and ALBUM_TRACKS would otherwise each fetch the same barcode
     * separately and, worse, each pick their own first artist-matching candidate, letting one
     * `enrich()` answer with metadata from one edition and a tracklist from another. Held for the
     * call only ([ProviderCallScope]); a provider used outside an engine gets its own memo per call.
     */
    private suspend fun upcMemo(): UpcLookupMemo =
        currentCoroutineContext()[ProviderCallScope]?.slot(this, ::UpcLookupMemo) ?: UpcLookupMemo()

    /**
     * This call's [ITunesAlbumScope], shared by the name-search branches of `enrichAlbumTracks` and
     * `enrichAlbumType` so a request answered by ALBUM_TRACKS together with ALBUM_ART/ALBUM_METADATA
     * selects one collection, not one independently-ranked collection per type ([ProviderCallScope],
     * `docs/pitfalls.md` §12). Composes with, and never replaces, [upcMemo] — an exact-id or barcode
     * hit still bypasses this scope entirely.
     */
    private suspend fun albumScope(): ITunesAlbumScope =
        currentCoroutineContext()[ProviderCallScope]?.slot(this) { ITunesAlbumScope(api) } ?: ITunesAlbumScope(api)

    /**
     * Resolves [barcode] to the album a human would mean by [artist] — [ITunesApi.lookupByUpc]
     * proves only that iTunes indexes the barcode somewhere, so this applies the same name gate
     * the search paths use ([ArtistMatcher]) before accepting a candidate. No match among the
     * candidates is a genuine miss, not a reason to fall back to search.
     */
    private suspend fun lookupByBarcode(barcode: String, artist: String): ITunesAlbumResult? =
        upcMemo().get { api.lookupByUpc(barcode) }.firstOrNull { ArtistMatcher.isMatch(artist, it.artistName) }

    override val id: String = "itunes"
    override val displayName: String = "iTunes"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(EnrichmentType.ALBUM_ART, priority = 40),
        ProviderCapability(EnrichmentType.ALBUM_METADATA, priority = 30),
        ProviderCapability(EnrichmentType.ALBUM_TRACKS, priority = 30),
        ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, priority = 30),
    )

    override suspend fun searchCandidates(
        request: EnrichmentRequest,
        limit: Int,
    ): List<SearchCandidate> {
        if (request !is EnrichmentRequest.ForAlbum) return emptyList()
        val term = "${request.artist} ${request.title}"
        return try {
            api.searchAlbums(term, limit).map { it.toCandidate() }
        } catch (_: Exception) {
            // A suspend call, and emptyList() returns without suspending again, so a cancelled
            // caller would otherwise be told the search simply found nothing. (#53)
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult = when (type) {
        EnrichmentType.ALBUM_TRACKS -> enrichAlbumTracks(request, type)
        EnrichmentType.ARTIST_DISCOGRAPHY -> enrichArtistDiscography(request, type)
        else -> enrichAlbumType(request, type)
    }

    private suspend fun enrichAlbumTracks(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForAlbum) {
            return EnrichmentResult.NotFound(type, id)
        }

        return try {
            // Try direct lookup if collectionId is already stored
            val collectionId = request.identifiers.get("itunesCollectionId")?.toLongOrNull()
            if (collectionId != null) {
                val tracks = api.lookupAlbumTracks(collectionId)
                if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
                return EnrichmentResult.Success(
                    type = type,
                    data = ITunesMapper.toTracklist(tracks),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
                    provenance = LookupProvenance.PROVIDER_NATIVE_ID,
                )
            }

            // A barcode is an identity lookup, shared with the other album types via upcMemo() so
            // a request asking metadata/art and tracks together resolves one collection, not two
            // independently search-ranked ones (see upcMemo KDoc).
            val barcode = request.identifiers.barcode
            if (!barcode.isNullOrBlank()) {
                val barcodeResult = lookupByBarcode(barcode, request.artist)
                    ?: return EnrichmentResult.NotFound(type, id)
                val barcodeTracks = api.lookupAlbumTracks(barcodeResult.collectionId)
                if (barcodeTracks.isEmpty()) return EnrichmentResult.NotFound(type, id)
                return EnrichmentResult.Success(
                    type = type,
                    data = ITunesMapper.toTracklist(barcodeTracks),
                    provider = id,
                    confidence = ConfidenceCalculator.idBasedLookup(),
                    resolvedIdentifiers = buildResolvedIdentifiers(barcodeResult),
                    // See enrichAlbumType's own EXTERNAL_CATALOG_ID comment for why.
                    provenance = LookupProvenance.EXTERNAL_CATALOG_ID,
                )
            }

            // Fall back to the shared name-search selection, so a call also asking ALBUM_ART or
            // ALBUM_METADATA resolves the same collection instead of ranking its own.
            val albumResult = albumScope().resolveAlbum(request)?.candidate
                ?: return EnrichmentResult.NotFound(type, id)

            val searchedCollectionId = albumResult.collectionId.takeIf { it > 0 }
                ?: return EnrichmentResult.NotFound(type, id)

            val tracks = api.lookupAlbumTracks(searchedCollectionId)
            if (tracks.isEmpty()) return EnrichmentResult.NotFound(type, id)

            val resolvedIdentifiers = buildResolvedIdentifiers(albumResult)
            EnrichmentResult.Success(
                type = type,
                data = ITunesMapper.toTracklist(tracks),
                provider = id,
                confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
                resolvedIdentifiers = resolvedIdentifiers,
            )
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichArtistDiscography(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForArtist) {
            return EnrichmentResult.NotFound(type, id)
        }

        return try {
            // Try direct lookup if artistId is already stored, else search — kept as two branches
            // (not one Elvis chain) so the id-versus-search distinction survives to provenance below.
            val storedArtistId = trustedProviderIdentifier(request, type)?.value?.toLongOrNull()
            val artistId = storedArtistId
                ?: api.searchArtist(request.name)
                ?: return EnrichmentResult.NotFound(type, id)

            val albums = api.lookupArtistAlbums(artistId)
            if (albums.isEmpty()) return EnrichmentResult.NotFound(type, id)

            EnrichmentResult.Success(
                type = type,
                data = ITunesMapper.toDiscography(albums),
                provider = id,
                confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true),
                resolvedIdentifiers = EnrichmentIdentifiers()
                    .with(IdentifierNamespace.ITUNES_ARTIST, artistId.toString()),
                provenance = if (storedArtistId != null) LookupProvenance.PROVIDER_NATIVE_ID else null,
            )
        } catch (e: Exception) {
            mapError(type, e)
        }
    }

    private suspend fun enrichAlbumType(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        val albumRequest = request.toAlbumArtRequest(type) ?: return EnrichmentResult.NotFound(type, id)

        // A barcode is an identity lookup, not a search — it replaces the search outright rather
        // than running before it, since the lookup is free on this call path but running it ahead
        // of an unused search would cost 3s of this provider's own serialised rate-limit budget
        // (RateLimiter(3000) above). No fallback to search on a miss: a barcode that names an
        // edition Apple never carries, or a candidate none of which name this artist, is a genuine
        // NotFound, not a reason to re-introduce the ranking risk the lookup exists to remove.
        // ISRC has no equivalent lookup — see ITunesApi.lookupByUpc KDoc.
        val barcode = albumRequest.identifiers.barcode
        if (!barcode.isNullOrBlank()) {
            val result = try {
                lookupByBarcode(barcode, albumRequest.artist)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                return mapError(type, e)
            } ?: return EnrichmentResult.NotFound(type, id)

            val confidence = ConfidenceCalculator.idBasedLookup()
            val resolvedIdentifiers = buildResolvedIdentifiers(result)
            // A barcode is an external catalogue identifier, not a MusicBrainz id or iTunes's own
            // id space.
            val idProvenance = LookupProvenance.EXTERNAL_CATALOG_ID
            return when (type) {
                EnrichmentType.ALBUM_METADATA ->
                    enrichAlbumMetadata(result, type, confidence, resolvedIdentifiers, idProvenance)
                else -> enrichAlbumArt(result, type, confidence, resolvedIdentifiers, idProvenance)
            }
        }

        val result = try {
            albumScope().resolveAlbum(albumRequest)?.candidate
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return mapError(type, e)
        } ?: return EnrichmentResult.NotFound(type, id)

        val confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true)
        return when (type) {
            EnrichmentType.ALBUM_METADATA -> enrichAlbumMetadata(result, type, confidence, null, provenance = null)
            else -> enrichAlbumArt(result, type, confidence, null, provenance = null)
        }
    }

    private fun enrichAlbumMetadata(
        result: ITunesAlbumResult,
        type: EnrichmentType,
        confidence: Float,
        resolvedIdentifiers: EnrichmentIdentifiers?,
        provenance: LookupProvenance?,
    ): EnrichmentResult = EnrichmentResult.Success(
        type = type,
        data = ITunesMapper.toAlbumMetadata(result),
        provider = id,
        confidence = confidence,
        resolvedIdentifiers = resolvedIdentifiers,
        provenance = provenance,
    )

    private fun enrichAlbumArt(
        result: ITunesAlbumResult,
        type: EnrichmentType,
        confidence: Float,
        resolvedIdentifiers: EnrichmentIdentifiers?,
        provenance: LookupProvenance?,
    ): EnrichmentResult {
        val artwork = ITunesMapper.toArtwork(result, artworkSize)
            ?: return EnrichmentResult.NotFound(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = artwork,
            provider = id,
            confidence = confidence,
            resolvedIdentifiers = resolvedIdentifiers,
            provenance = provenance,
        )
    }

    /**
     * [enrichAlbumType]'s own request shape: a [EnrichmentRequest.ForAlbum] as-is for any type, or
     * — for [EnrichmentType.ALBUM_ART] only — a [EnrichmentRequest.ForTrack] carrying a non-blank
     * [EnrichmentRequest.ForTrack.album] treated as one, its album standing in for the title. A
     * `ForTrack` never widens `ALBUM_METADATA`: that type keeps requiring a genuine `ForAlbum`.
     */
    private fun EnrichmentRequest.toAlbumArtRequest(type: EnrichmentType): EnrichmentRequest.ForAlbum? = when {
        this is EnrichmentRequest.ForAlbum -> this
        this is EnrichmentRequest.ForTrack && type == EnrichmentType.ALBUM_ART && !album.isNullOrBlank() ->
            EnrichmentRequest.ForAlbum(identifiers = identifiers, title = album, artist = artist)
        else -> null
    }

    private fun buildResolvedIdentifiers(result: ITunesAlbumResult): EnrichmentIdentifiers? {
        val collectionId = result.collectionId.takeIf { it > 0 } ?: return null
        return EnrichmentIdentifiers().withExtra("itunesCollectionId", collectionId.toString())
    }

    private fun ITunesAlbumResult.toCandidate(): SearchCandidate =
        ITunesMapper.toSearchCandidate(this, id, SEARCH_SCORE)

    /**
     * One `lookup?upc=` answer per call, held whatever it is (including "no candidates") — the
     * mutex is across [fetch] itself, not just the cache, since the engine resolves a request's
     * types as sibling `async` children and two of them asking for the same barcode must make one
     * call between them, matching `com.landofoz.musicmeta.engine.CallMemo`'s shape. A thrown transient is
     * never held, so the next type that asks retries it.
     */
    private class UpcLookupMemo {
        private val mutex = Mutex()
        private var candidates: List<ITunesAlbumResult>? = null

        suspend fun get(fetch: suspend () -> List<ITunesAlbumResult>): List<ITunesAlbumResult> =
            mutex.withLock { candidates ?: fetch().also { candidates = it } }
    }

    public companion object {
        public const val DEFAULT_ARTWORK_SIZE: Int = 1200
        private const val SEARCH_SCORE = 70
    }
}

/**
 * One `enrich()` call's name-search selection, held only long enough to serve the search-fallback
 * branches of `ALBUM_TRACKS`, `ALBUM_METADATA` and `ALBUM_ART` from one search and one accepted
 * collection instead of one independently-ranked search per type — see [ITunesProvider.albumScope].
 * Dies with the call ([ProviderCallScope]), so a mis-resolved artist/title never outlives a
 * `forceRefresh`. Exact `itunesCollectionId` and barcode/UPC lookups never reach this scope.
 */
private class ITunesAlbumScope(private val api: ITunesApi) {

    /** Every field [selectAlbum] reads, so two requests differing only in one still key distinctly. */
    private data class SelectionKey(val artist: String, val title: String, val trackCount: Int?, val year: Int?)

    private val mutex = Mutex()
    private val results = mutableMapOf<SelectionKey, AlbumMatch<ITunesAlbumResult>?>()

    /**
     * The accepted-and-ranked search hit for [request], with its selection evidence, one search per
     * distinct complete selection input per call. Keyed on every field selection reads — including
     * `trackCount` and `year` — so a request differing only in those cannot reuse a stale selection.
     */
    suspend fun resolveAlbum(request: EnrichmentRequest.ForAlbum): AlbumMatch<ITunesAlbumResult>? {
        val key = SelectionKey(request.artist, request.title, request.trackCount, request.year)
        return mutex.withLock {
            if (results.containsKey(key)) {
                results.getValue(key)
            } else {
                val term = "${request.artist} ${request.title}"
                val match = api.searchAlbums(term, ALBUM_SEARCH_LIMIT).selectAlbum(request)
                results[key] = match
                match
            }
        }
    }

    private companion object {
        /** Candidate pool size for the name-search selection — enough hits for the requested edition to surface. */
        const val ALBUM_SEARCH_LIMIT = 5
    }
}
