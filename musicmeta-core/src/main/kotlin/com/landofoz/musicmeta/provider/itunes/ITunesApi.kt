package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.bestArtistMatch
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.takeIfNotEmpty
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * iTunes Search API. Free, no authentication required.
 * Rate limit: ~20 requests/minute.
 */
internal class ITunesApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchAlbums(term: String, limit: Int): List<ITunesAlbumResult> {
        val encoded = URLEncoder.encode(term, "UTF-8")
        val url = "$BASE_URL/search?media=music&entity=album&term=$encoded&limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val album = results.getJSONObject(i)
            parseAlbumResult(album)
        }
    }

    suspend fun lookupAlbumTracks(collectionId: Long): List<ITunesTrackResult> {
        val url = "$BASE_URL/lookup?id=$collectionId&entity=song"
        val json = fetchJson(url) ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            if (item.optString("wrapperType") != "track") return@mapNotNull null
            ITunesTrackResult(
                trackId = item.optLong("trackId"),
                trackName = item.optString("trackName", ""),
                trackNumber = item.optInt("trackNumber", 0),
                trackTimeMillis = item.optLong("trackTimeMillis").takeIf { it > 0 },
                artistName = item.optString("artistName", ""),
                collectionName = item.optString("collectionName").takeIfNotEmpty(),
            )
        }
    }

    suspend fun lookupArtistAlbums(artistId: Long): List<ITunesAlbumResult> {
        val url = "$BASE_URL/lookup?id=$artistId&entity=album"
        val json = fetchJson(url) ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            if (item.optString("wrapperType") != "collection") return@mapNotNull null
            parseAlbumResult(item)
        }
    }

    /**
     * Resolves a barcode to the album(s) iTunes lists under it — an identity lookup, not a text
     * search: no ranking against a query string, no ambiguity about *which* field matched. A `200`
     * with `resultCount: 0` means "no album carries this barcode", so an empty return is absence,
     * never an outage.
     *
     * Returns every `collection` result, not one — a barcode can be reused across editions or, per
     * the ticket that added this call, even land on entirely unrelated real entries (a probed
     * placeholder barcode alone returned 24). **The caller must still filter by artist name before
     * trusting a hit**, the same gate the search paths apply; this call only proves "iTunes indexes
     * this barcode somewhere", not "this is the right album".
     *
     * ISRC has no equivalent: `lookup?isrc=` returns `200 resultCount: 0` for an ISRC known to be
     * in catalogue, so it is silently unsupported rather than broken — do not add an `isrc`
     * parameter to this call assuming the same shape works.
     *
     * Apple answers some malformed parameters the same way it answers a genuine miss — `200` with
     * an `errorMessage` body and no `results` key — so if `upc`'s shape ever changes upstream, every
     * barcode album goes quietly `NotFound` while the circuit breaker stays healthy.
     */
    suspend fun lookupByUpc(upc: String): List<ITunesAlbumResult> {
        val encoded = URLEncoder.encode(upc, "UTF-8")
        val url = "$BASE_URL/lookup?upc=$encoded"
        val json = fetchJson(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length())
            .mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("wrapperType") == "collection" }
            .map(::parseAlbumResult)
    }

    /**
     * Finds the artist a human would mean by [artistName].
     *
     * A search API's hit 0 is a ranking, not an answer (docs/pitfalls.md §7), and this call had no
     * name check at all — `enrichArtistDiscography` took whatever came back. So fetch a pool and
     * keep only the candidates [ArtistMatcher] accepts, ranked by [ArtistMatcher.matchQuality] so a
     * closer name always beats a looser one.
     *
     * There is no popularity tiebreak because **the payload carries no popularity signal**: an
     * iTunes `musicArtist` result is only `artistId`, `artistName`, `artistType`, `artistLinkUrl`,
     * `primaryGenreName`/`Id` and sometimes `amgArtistId` (an AllMusic identifier, not a measure).
     * Nothing separates the four entries named exactly "Nirvana". So equal-quality candidates fall
     * back to iTunes' own order — [bestArtistMatch] keeps the first maximum — which makes this a
     * strict narrowing of the old behaviour rather than a re-ranking of it.
     */
    suspend fun searchArtist(artistName: String): Long? {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val url = "$BASE_URL/search?media=music&entity=musicArtist" +
            "&term=$encoded&limit=$ARTIST_SEARCH_LIMIT"
        val json = fetchJson(url) ?: return null
        val results = json.optJSONArray("results") ?: return null
        val artistId = (0 until results.length())
            // A non-object element is skipped, not thrown: a JSONException here would surface as
            // Error and open the breaker against a healthy iTunes (docs/pitfalls.md §4).
            .mapNotNull { results.optJSONObject(it) }
            .bestArtistMatch(artistName) { it.optString("artistName", "") }
            ?.optLong("artistId") ?: return null
        return if (artistId > 0) artistId else null
    }

    /**
     * The single fetch every call above goes through: rate limit, then classification.
     *
     * On top of the shared [bodyOrThrowTransient] rules, **a 403 from iTunes is a throttle**, not a
     * client error — thrown as [IOException] so it travels the provider's existing
     * `catch { mapError(type, e) }` into `Error(ErrorKind.NETWORK)` rather than collapsing to
     * `NotFound` and recording a breaker *success* on a throttled provider (`docs/pitfalls.md` §4).
     *
     * **Undocumented, and deliberately iTunes-local.** Apple's Search API doc states the ~20
     * calls/minute limit but never the response; the 403 is only acknowledged by Apple staff on
     * Apple's own developer forums (thread 66399) and reproduced by the community. It is not a
     * contract and may change without notice. It is safe to read this way *here* because these
     * endpoints send no credentials and have no other documented 403 — which is exactly why it must
     * not go into the shared helper, where a keyed provider's 403 means a rejected key.
     *
     * The response carries no `Retry-After` and no `X-RateLimit-*`, so there is no wait hint to
     * preserve.
     */
    private suspend fun fetchJson(url: String): JSONObject? = rateLimiter.execute {
        val result = httpClient.fetchJsonResult(url)
        if (result is HttpResult.ClientError && result.statusCode == THROTTLE_STATUS) {
            throw IOException("HTTP 403: iTunes rate limited")
        }
        result.bodyOrThrowTransient()
    }

    private fun parseAlbumResult(album: JSONObject): ITunesAlbumResult =
        ITunesAlbumResult(
            collectionId = album.optLong("collectionId"),
            collectionName = album.optString("collectionName", ""),
            artistName = album.optString("artistName", ""),
            artworkUrl = album.optString("artworkUrl100").takeIfNotEmpty(),
            releaseDate = album.optString("releaseDate").takeIfNotEmpty(),
            primaryGenreName = album.optString("primaryGenreName").takeIfNotEmpty(),
            country = album.optString("country").takeIfNotEmpty(),
            trackCount = album.optInt("trackCount", 0).takeIf { it > 0 },
        )

    companion object {
        const val BASE_URL = "https://itunes.apple.com"

        /** Candidate pool size for artist search — enough hits for a wrong name to be passed over. */
        const val ARTIST_SEARCH_LIMIT = 10

        /** iTunes answers a rate limit with 403; see [fetchJson] for why that reading is safe here. */
        private const val THROTTLE_STATUS = 403
    }
}
