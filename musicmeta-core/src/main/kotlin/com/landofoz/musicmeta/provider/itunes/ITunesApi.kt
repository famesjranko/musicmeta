package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.takeIfNotEmpty
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
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val album = results.getJSONObject(i)
            parseAlbumResult(album)
        }
    }

    suspend fun lookupAlbumTracks(collectionId: Long): List<ITunesTrackResult> {
        val url = "$BASE_URL/lookup?id=$collectionId&entity=song"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

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
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            if (item.optString("wrapperType") != "collection") return@mapNotNull null
            parseAlbumResult(item)
        }
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
     * back to iTunes' own order — [maxWithOrNull] keeps the first maximum — which makes this a
     * strict narrowing of the old behaviour rather than a re-ranking of it.
     */
    suspend fun searchArtist(artistName: String): Long? {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val url = "$BASE_URL/search?media=music&entity=musicArtist" +
            "&term=$encoded&limit=$ARTIST_SEARCH_LIMIT"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        val results = json.optJSONArray("results") ?: return null
        val artistId = (0 until results.length())
            // A non-object element is skipped, not thrown: a JSONException here would surface as
            // Error and open the breaker against a healthy iTunes (docs/pitfalls.md §4).
            .mapNotNull { results.optJSONObject(it) }
            .filter { ArtistMatcher.isMatch(artistName, it.optString("artistName", "")) }
            .maxWithOrNull(compareBy { ArtistMatcher.matchQuality(artistName, it.optString("artistName", "")) })
            ?.optLong("artistId") ?: return null
        return if (artistId > 0) artistId else null
    }

    private fun parseAlbumResult(album: org.json.JSONObject): ITunesAlbumResult =
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
    }
}
