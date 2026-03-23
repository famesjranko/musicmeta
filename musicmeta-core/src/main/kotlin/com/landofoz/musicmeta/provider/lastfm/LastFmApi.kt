package com.landofoz.musicmeta.provider.lastfm

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Last.fm API client. Requires an API key from https://www.last.fm/api.
 * Rate limited to 5 requests/second (200ms interval).
 */
class LastFmApi(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(apiKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ apiKey }, httpClient, rateLimiter)

    suspend fun getArtistInfo(artistName: String): LastFmArtistInfo? {
        val url = buildUrl("artist.getinfo", artistName)
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return parseArtistInfo(json)
    }

    suspend fun getSimilarArtists(artistName: String): List<LastFmSimilarArtist> {
        val url = buildUrl("artist.getsimilar", artistName) + "&limit=20"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return parseSimilarArtists(json)
    }

    suspend fun getArtistTopTags(artistName: String): List<String> {
        val url = buildUrl("artist.getinfo", artistName)
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return parseTags(json)
    }

    suspend fun getSimilarTracks(trackTitle: String, artistName: String, limit: Int = 20): List<LastFmSimilarTrack> {
        val url = buildTrackUrl("track.getsimilar", trackTitle, artistName) + "&limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return parseSimilarTracks(json)
    }

    suspend fun getArtistTopTracks(artistName: String, limit: Int = 20): List<LastFmTopTrack> {
        val url = buildUrl("artist.gettoptracks", artistName) + "&limit=$limit"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return parseTopTracks(json)
    }

    suspend fun getAlbumInfo(album: String, artist: String): LastFmAlbumInfo? {
        val url = buildAlbumUrl("album.getinfo", album, artist)
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return parseAlbumInfo(json)
    }

    suspend fun getTrackInfo(trackTitle: String, artistName: String): LastFmTrackInfo? {
        val url = buildTrackUrl("track.getInfo", trackTitle, artistName)
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return parseTrackInfo(json)
    }

    private fun buildAlbumUrl(method: String, album: String, artist: String): String {
        val encodedAlbum = URLEncoder.encode(album, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        return "$BASE_URL?method=$method&album=$encodedAlbum&artist=$encodedArtist&api_key=${apiKeyProvider()}&format=json"
    }

    private fun buildTrackUrl(method: String, trackTitle: String, artistName: String): String {
        val encodedTrack = URLEncoder.encode(trackTitle, "UTF-8")
        val encodedArtist = URLEncoder.encode(artistName, "UTF-8")
        return "$BASE_URL?method=$method&track=$encodedTrack&artist=$encodedArtist&api_key=${apiKeyProvider()}&format=json"
    }

    private fun buildUrl(method: String, artistName: String): String {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        return "$BASE_URL?method=$method&artist=$encoded&api_key=${apiKeyProvider()}&format=json"
    }

    private fun parseArtistInfo(json: JSONObject): LastFmArtistInfo? {
        val artist = json.optJSONObject("artist") ?: return null
        val bio = artist.optJSONObject("bio")?.optString("summary")?.takeIf { it.isNotBlank() }
        val stats = artist.optJSONObject("stats")
        return LastFmArtistInfo(
            name = artist.optString("name", ""),
            bio = bio,
            tags = parseTags(json),
            listeners = stats?.optString("listeners")?.toLongOrNull(),
            playcount = stats?.optString("playcount")?.toLongOrNull(),
        )
    }

    private fun parseSimilarArtists(json: JSONObject): List<LastFmSimilarArtist> {
        val container = json.optJSONObject("similarartists") ?: return emptyList()
        val array = container.optJSONArray("artist") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            LastFmSimilarArtist(
                name = obj.optString("name", ""),
                matchScore = obj.optString("match", "0").toFloatOrNull() ?: 0f,
                mbid = obj.optString("mbid").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parseSimilarTracks(json: JSONObject): List<LastFmSimilarTrack> {
        val container = json.optJSONObject("similartracks") ?: return emptyList()
        val array = container.optJSONArray("track") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val artistObj = obj.optJSONObject("artist")
            LastFmSimilarTrack(
                title = obj.optString("name", ""),
                artist = artistObj?.optString("name", "") ?: "",
                matchScore = obj.optString("match", "0").toFloatOrNull() ?: 0f,
                mbid = obj.optString("mbid").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parseTrackInfo(json: JSONObject): LastFmTrackInfo? {
        val track = json.optJSONObject("track") ?: return null
        val artist = track.optJSONObject("artist")
        return LastFmTrackInfo(
            title = track.optString("name", ""),
            artist = artist?.optString("name", "") ?: "",
            playcount = track.optString("playcount")?.toLongOrNull(),
            listeners = track.optString("listeners")?.toLongOrNull(),
            mbid = track.optString("mbid").takeIf { it.isNotBlank() },
        )
    }

    private fun parseAlbumInfo(json: JSONObject): LastFmAlbumInfo? {
        val album = json.optJSONObject("album") ?: return null
        val artistVal = album.optJSONObject("artist")?.optString("name")
            ?: album.optString("artist", "")
        val tagsObj = album.optJSONObject("tags")
        val tagArray = tagsObj?.optJSONArray("tag")
        val tags = if (tagArray != null) {
            (0 until tagArray.length()).map { i ->
                tagArray.getJSONObject(i).optString("name", "")
            }.filter { it.isNotBlank() }
        } else emptyList()
        val wiki = album.optJSONObject("wiki")?.optString("summary")?.takeIf { it.isNotBlank() }
        val tracksObj = album.optJSONObject("tracks")
        val trackCount = tracksObj?.optJSONArray("track")?.length()?.takeIf { it > 0 }
        return LastFmAlbumInfo(
            name = album.optString("name", ""),
            artist = artistVal,
            playcount = album.optString("playcount")?.toLongOrNull(),
            listeners = album.optString("listeners")?.toLongOrNull(),
            tags = tags,
            wiki = wiki,
            trackCount = trackCount,
        )
    }

    private fun parseTopTracks(json: JSONObject): List<LastFmTopTrack> {
        val container = json.optJSONObject("toptracks") ?: return emptyList()
        val array = container.optJSONArray("track") ?: return emptyList()
        return (0 until array.length()).mapIndexed { index, i ->
            val obj = array.getJSONObject(i)
            val artistObj = obj.optJSONObject("artist")
            LastFmTopTrack(
                title = obj.optString("name", ""),
                artist = artistObj?.optString("name", "") ?: "",
                playcount = obj.optString("playcount")?.toLongOrNull(),
                listeners = obj.optString("listeners")?.toLongOrNull(),
                mbid = obj.optString("mbid").takeIf { it.isNotBlank() },
                rank = index + 1,
            )
        }
    }

    private fun parseTags(json: JSONObject): List<String> {
        val artist = json.optJSONObject("artist") ?: return emptyList()
        val tags = artist.optJSONObject("tags") ?: return emptyList()
        val tagArray = tags.optJSONArray("tag") ?: return emptyList()
        return (0 until tagArray.length()).map { i ->
            tagArray.getJSONObject(i).optString("name", "")
        }.filter { it.isNotBlank() }
    }

    private companion object {
        const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
    }
}
