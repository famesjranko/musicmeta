package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.bestArtistMatch
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.takeIfNotEmpty
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Deezer public search API. No authentication required.
 * Rate limit: ~10 requests/second.
 */
internal class DeezerApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchAlbums(query: String, limit: Int): List<DeezerAlbumResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/album?q=$encoded&limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val album = data.getJSONObject(i)
            val artist = album.optJSONObject("artist")
            DeezerAlbumResult(
                id = album.optLong("id"),
                title = album.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                coverSmall = album.optString("cover_small").takeIfNotEmpty(),
                coverMedium = album.optString("cover_medium").takeIfNotEmpty(),
                coverBig = album.optString("cover_big").takeIfNotEmpty(),
                coverXl = album.optString("cover_xl").takeIfNotEmpty(),
                nbTracks = album.optInt("nb_tracks", 0).takeIf { it > 0 },
                recordType = album.optString("record_type").takeIfNotEmpty(),
                explicitLyrics = if (album.has("explicit_lyrics")) album.optBoolean("explicit_lyrics") else null,
            )
        }
    }

    /**
     * Finds the artist a human would mean by [name].
     *
     * Deezer's result order is not trustworthy: "Radiohead" returns an exact-name ghost
     * (0 albums, 470 fans) ahead of the real artist (id 399). So fetch a pool of candidates,
     * keep the ones [ArtistMatcher.isMatch] accepts, and rank those by name quality first
     * ([ArtistMatcher.matchQuality]) and only then by fans and albums.
     *
     * Name quality outranks popularity at every step, so a popular wrong-name artist cannot win:
     * not one the matcher rejects, and not one it accepts only loosely while a closer name is in
     * the pool. Popularity decides between candidates whose names are equally good — which is the
     * ghost case, two entries both named exactly "Radiohead".
     */
    suspend fun searchArtist(name: String): DeezerArtistSearchResult? {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$BASE_URL/search/artist?q=$encoded&limit=$ARTIST_SEARCH_LIMIT"
        val json = fetchJson(url) ?: return null

        val data = json.optJSONArray("data") ?: return null
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it) }
            .bestArtistMatch(
                expected = name,
                tieBreak = compareBy({ it.optLong("nb_fan") }, { it.optLong("nb_album") }),
            ) { it.optString("name", "") }
            ?.let { artist ->
                DeezerArtistSearchResult(
                    id = artist.optLong("id"),
                    name = artist.optString("name", ""),
                    pictureSmall = artist.optString("picture_small").takeIfNotEmpty(),
                    pictureMedium = artist.optString("picture_medium").takeIfNotEmpty(),
                    pictureBig = artist.optString("picture_big").takeIfNotEmpty(),
                    pictureXl = artist.optString("picture_xl").takeIfNotEmpty(),
                )
            }
    }

    suspend fun getArtistAlbums(artistId: Long, limit: Int = 50): List<DeezerArtistAlbum> {
        val url = "$BASE_URL/artist/$artistId/albums?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val album = data.getJSONObject(i)
            DeezerArtistAlbum(
                id = album.optLong("id"),
                title = album.optString("title", ""),
                releaseDate = album.optString("release_date").takeIfNotEmpty(),
                recordType = album.optString("record_type").takeIfNotEmpty(),
                coverSmall = album.optString("cover_small").takeIfNotEmpty(),
                coverMedium = album.optString("cover_medium").takeIfNotEmpty(),
            )
        }
    }

    suspend fun getAlbumTracks(albumId: Long): List<DeezerTrack> {
        val url = "$BASE_URL/album/$albumId/tracks"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            DeezerTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                trackPosition = track.optInt("track_position", 0),
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    suspend fun getRelatedArtists(artistId: Long, limit: Int = 20): List<DeezerRelatedArtist> {
        val url = "$BASE_URL/artist/$artistId/related?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val artist = data.getJSONObject(i)
            DeezerRelatedArtist(
                id = artist.optLong("id"),
                name = artist.optString("name", ""),
            )
        }
    }

    suspend fun searchTrack(title: String, artist: String): DeezerTrackSearchResult? {
        val query = URLEncoder.encode("$artist $title", "UTF-8")
        val url = "$BASE_URL/search/track?q=$query&limit=5"
        val json = fetchJson(url) ?: return null

        val data = json.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val track = data.getJSONObject(i)
            val trackArtist = track.optJSONObject("artist")
            val artistName = trackArtist?.optString("name", "").orEmpty()
            return DeezerTrackSearchResult(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artistName,
                previewUrl = track.optString("preview").takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration").takeIf { it > 0 },
                albumTitle = track.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    suspend fun getTrackRadio(trackId: Long, limit: Int = 25): List<DeezerRadioTrack> {
        val url = "$BASE_URL/track/$trackId/radio?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val trackArtist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerRadioTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = trackArtist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    suspend fun getArtistTop(artistId: Long, limit: Int = 10): List<DeezerTopTrack> {
        val url = "$BASE_URL/artist/$artistId/top?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val artist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerTopTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
                rank = track.optInt("rank", 0),
            )
        }
    }

    suspend fun getArtistRadio(artistId: Long, limit: Int = 25): List<DeezerRadioTrack> {
        val url = "$BASE_URL/artist/$artistId/radio?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val artist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerRadioTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    /** Fetches a single track by Deezer ID. Returns preview URL and metadata. */
    suspend fun getTrack(trackId: Long): DeezerTrackSearchResult? {
        val json = fetchJson("$BASE_URL/track/$trackId") ?: return null
        val artist = json.optJSONObject("artist")
        return DeezerTrackSearchResult(
            id = json.optLong("id"),
            title = json.optString("title", ""),
            artistName = artist?.optString("name", "").orEmpty(),
            previewUrl = json.optString("preview").takeIf { it.isNotBlank() },
            durationSec = json.optInt("duration").takeIf { it > 0 },
            albumTitle = json.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The single fetch every call above goes through: rate limit, HTTP-level transient
     * classification ([bodyOrThrowTransient]), then Deezer's own application-level one.
     *
     * Deezer answers application errors with **HTTP 200** and an `error` object in place of the
     * payload, so no HTTP-layer classification can see them — `{"error":{"type":"DataException",
     * "message":"no data","code":800}}` was observed live for a missing artist. Left alone, every
     * caller's `optJSONArray("data")` reads that as an empty result, and a throttled Deezer records
     * a breaker *success* while answering "nothing" to everything (`docs/pitfalls.md` §4).
     *
     * The split: `code == 4` is the quota rejection, thrown as [IOException] so it travels the
     * provider's existing `catch { mapError(type, e) }` into `Error(ErrorKind.NETWORK)`. Any other
     * `error` object is a genuine "no such thing" and returns `null`, which stays `NotFound`.
     *
     * **`code == 4` is a heuristic, not a contract.** Deezer's own error-code table is login-gated,
     * so there is no citable official list; the quota shape is community-corroborated only, and may
     * change without notice. Widen it when another transient code is actually observed — not
     * speculatively.
     */
    private suspend fun fetchJson(url: String): JSONObject? = rateLimiter.execute {
        val json = httpClient.fetchJsonResult(url).bodyOrThrowTransient() ?: return@execute null
        val error = json.optJSONObject("error") ?: return@execute json
        if (error.optInt("code") == QUOTA_ERROR_CODE) {
            throw IOException("Deezer quota exceeded: ${error.optString("message")}")
        }
        null
    }

    private companion object {
        const val BASE_URL = "https://api.deezer.com"

        /** Candidate pool size for artist search — enough hits for a ghost to be outvoted. */
        const val ARTIST_SEARCH_LIMIT = 10

        /** Deezer's "Quota limit exceeded" code, carried in a 200 response's `error` object. */
        const val QUOTA_ERROR_CODE = 4
    }
}
