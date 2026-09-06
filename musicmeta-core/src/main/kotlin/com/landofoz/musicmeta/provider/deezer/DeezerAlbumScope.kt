package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.AlbumMatch
import com.landofoz.musicmeta.engine.CallMemo
import com.landofoz.musicmeta.engine.ProviderCallScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One `enrich()` call's album search hit and album-resource detail, held only long enough to serve
 * every type that asks this Deezer API for the same album from one search and one `/album/{id}`
 * fetch. Dies with the call ([ProviderCallScope]), so a mis-resolved artist/title never outlives a
 * `forceRefresh`.
 *
 * Its readers are the types of both Deezer providers — `ALBUM_TRACKS`, `ALBUM_METADATA` and
 * `ALBUM_ART` from [DeezerProvider], and [SimilarAlbumsProvider]'s seed — which is why it belongs
 * to [DeezerApi] and not to either of them ([albumScope]).
 */
internal class DeezerAlbumScope(private val api: DeezerApi) {

    /** Every field [selectAlbum] reads, so two requests differing only in one still key distinctly. */
    private data class SelectionKey(val artist: String, val title: String, val trackCount: Int?)

    private val searches = CallMemo<SelectionKey, Result<AlbumMatch<DeezerAlbumResult>?>>()
    private val details = CallMemo<Long, DeezerAlbum?>()

    /**
     * The accepted-and-ranked search hit for [request], with its selection evidence, one search per
     * distinct complete selection input per call. Selection is [selectAlbum]'s: artist floor, then
     * title tier, then artist quality, then [EnrichmentRequest.ForAlbum.trackCount] evidence — never
     * bare artist-match order. Keyed on every field selection reads, not just artist/title: two
     * requests differing only in `trackCount` must not reuse each other's selection.
     *
     * A failure is held on the same terms as an answer, because four types across two providers now
     * queue on one query behind one Deezer rate limiter: the first reader's failed attempt is
     * rethrown to the others rather than each spending its own against an endpoint already known to
     * be failing (`docs/pitfalls.md` §23). `ensureActive()` decides which failures are eligible —
     * only this job's own cancellation escapes the memo, leaving nothing for a sibling to inherit.
     * The cost of sharing is that a transient recovering *between* two readers no longer reaches the
     * second.
     */
    suspend fun resolveAlbum(request: EnrichmentRequest.ForAlbum): AlbumMatch<DeezerAlbumResult>? {
        val key = SelectionKey(request.artist, request.title, request.trackCount)
        return searches.get(key) {
            try {
                val results = api.searchAlbums("${request.artist} ${request.title}", ALBUM_SEARCH_LIMIT)
                Result.success(results.selectAlbum(request))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
        }.getOrThrow()
    }

    /** The `/album/{id}` resource for [albumId], one fetch per distinct id per call. */
    suspend fun albumDetail(albumId: Long): DeezerAlbum? = details.get(albumId) { api.getAlbum(albumId) }
}

/**
 * This call's [DeezerAlbumScope] for the API it is asked of, so no two types search or fetch the
 * same album twice in one `enrich()` fan-out ([ProviderCallScope], `docs/pitfalls.md` §12).
 *
 * Keyed on the [DeezerApi] rather than on the asking provider: the scope holds which album a
 * name resolves to, which is only safe to share between providers that reach Deezer through the
 * same client and the same rate limiter, and holding the same API instance is exactly that.
 * Providers wired with an API each keep a scope each. Called outside an engine, every call gets its
 * own.
 */
internal suspend fun DeezerApi.albumScope(): DeezerAlbumScope {
    val api = this
    return currentCoroutineContext()[ProviderCallScope]?.slot(api) { DeezerAlbumScope(api) }
        ?: DeezerAlbumScope(api)
}

/** Candidate pool size for the artist-matched album search — enough hits for the requested edition to surface. */
private const val ALBUM_SEARCH_LIMIT = 5
