package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Discogs API client. Requires a personal access token.
 * Rate limited to 60 requests/minute (1000ms interval).
 */
class DiscogsApi(
    private val tokenProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(personalToken: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ personalToken }, httpClient, rateLimiter)

    suspend fun searchReleases(title: String, artist: String, limit: Int = 5): List<DiscogsRelease> {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val url = "$SEARCH_URL?type=release&title=$encodedTitle" +
            "&artist=$encodedArtist&per_page=$limit&token=${tokenProvider()}"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return parseReleaseResults(json)
    }

    /** Search for an artist by name and return the first match's Discogs ID. */
    suspend fun searchArtist(name: String): Long? {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$SEARCH_URL?type=artist&q=$encoded&per_page=1&token=${tokenProvider()}"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val id = results.getJSONObject(0).optLong("id", 0L)
        return if (id > 0) id else null
    }

    /** Fetch artist details including band members. */
    suspend fun getArtist(artistId: Long): DiscogsArtist? {
        val url = "$ARTISTS_URL/$artistId?token=${tokenProvider()}"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return parseArtist(json)
    }

    /** Fetch all versions of a master release (pressings, editions). */
    suspend fun getMasterVersions(masterId: Long): List<DiscogsMasterVersion> {
        val url = "$MASTERS_URL/$masterId/versions?per_page=100&token=${tokenProvider()}"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return emptyList()
        return parseMasterVersions(json)
    }

    /** Fetch release details including extraartists and tracklist. */
    suspend fun getReleaseDetails(releaseId: Long): DiscogsReleaseDetail? {
        val url = "$RELEASES_URL/$releaseId?token=${tokenProvider()}"
        val json = rateLimiter.execute {
            when (val r = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> r.body
                else -> return@execute null
            }
        } ?: return null
        return parseReleaseDetail(json)
    }

    private fun parseReleaseDetail(json: JSONObject): DiscogsReleaseDetail {
        val extraartists = parseCreditsArray(json.optJSONArray("extraartists"))
        val tracklistArr = json.optJSONArray("tracklist")
        val tracklist = if (tracklistArr != null) {
            (0 until tracklistArr.length()).map { i ->
                val track = tracklistArr.getJSONObject(i)
                DiscogsTrackItem(
                    title = track.optString("title", ""),
                    position = track.optString("position", ""),
                    extraartists = parseCreditsArray(track.optJSONArray("extraartists")),
                )
            }
        } else emptyList()
        val community = json.optJSONObject("community")
        val ratingObj = community?.optJSONObject("rating")
        val communityRating = ratingObj?.optDouble("average", 0.0)?.toFloat()?.takeIf { it > 0f }
        val ratingCount = ratingObj?.optInt("count", 0)?.takeIf { it > 0 }
        val haveCount = community?.optInt("have", 0)?.takeIf { it > 0 }
        val wantCount = community?.optInt("want", 0)?.takeIf { it > 0 }
        return DiscogsReleaseDetail(
            id = json.optLong("id", 0L),
            title = json.optString("title", ""),
            extraartists = extraartists,
            tracklist = tracklist,
            communityRating = communityRating,
            ratingCount = ratingCount,
            haveCount = haveCount,
            wantCount = wantCount,
        )
    }

    private fun parseCreditsArray(arr: org.json.JSONArray?): List<DiscogsCredit> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            DiscogsCredit(
                name = obj.optString("name", ""),
                role = obj.optString("role", ""),
                id = obj.optLong("id", 0L).takeIf { it > 0 },
            )
        }
    }

    private fun parseArtist(json: JSONObject): DiscogsArtist {
        val members = mutableListOf<DiscogsMember>()
        val membersArray = json.optJSONArray("members")
        if (membersArray != null) {
            for (i in 0 until membersArray.length()) {
                val obj = membersArray.getJSONObject(i)
                members.add(
                    DiscogsMember(
                        id = obj.optLong("id", 0L),
                        name = obj.optString("name", ""),
                        active = if (obj.has("active")) obj.optBoolean("active") else null,
                    ),
                )
            }
        }
        val images = mutableListOf<DiscogsImage>()
        val imagesArray = json.optJSONArray("images")
        if (imagesArray != null) {
            for (i in 0 until imagesArray.length()) {
                val img = imagesArray.getJSONObject(i)
                val uri = img.optString("uri").takeIf { it.isNotBlank() } ?: continue
                images.add(
                    DiscogsImage(
                        type = img.optString("type", "secondary"),
                        uri = uri,
                        uri150 = img.optString("uri150").takeIf { it.isNotBlank() },
                        width = img.optInt("width", 0).takeIf { it > 0 },
                        height = img.optInt("height", 0).takeIf { it > 0 },
                    ),
                )
            }
        }
        return DiscogsArtist(
            id = json.optLong("id", 0L),
            name = json.optString("name", ""),
            members = members,
            images = images,
        )
    }

    private fun parseMasterVersions(json: JSONObject): List<DiscogsMasterVersion> {
        val versions = json.optJSONArray("versions") ?: return emptyList()
        return (0 until versions.length()).map { i ->
            val obj = versions.getJSONObject(i)
            DiscogsMasterVersion(
                id = obj.optLong("id", 0L),
                title = obj.optString("title", ""),
                format = obj.optString("format").takeIf { it.isNotBlank() },
                label = obj.optString("label").takeIf { it.isNotBlank() },
                country = obj.optString("country").takeIf { it.isNotBlank() },
                year = obj.optInt("year", 0).takeIf { it > 0 },
                catno = obj.optString("catno").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parseReleaseResults(json: JSONObject): List<DiscogsRelease> {
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val obj = results.getJSONObject(i)
            val labels = obj.optJSONArray("label")
            val label = if (labels != null && labels.length() > 0) labels.getString(0) else null
            val genreArr = obj.optJSONArray("genre")
            val genres = genreArr?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            val styleArr = obj.optJSONArray("style")
            val styles = styleArr?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            DiscogsRelease(
                title = obj.optString("title", ""),
                label = label,
                year = obj.optString("year").takeIf { it.isNotBlank() },
                country = obj.optString("country").takeIf { it.isNotBlank() },
                coverImage = obj.optString("cover_image").takeIf { it.isNotBlank() },
                releaseType = obj.optString("type").takeIf { it.isNotBlank() },
                catno = obj.optString("catno").takeIf { it.isNotBlank() },
                genres = genres,
                styles = styles,
                releaseId = obj.optLong("id", 0L).takeIf { it > 0 },
                masterId = obj.optLong("master_id", 0L).takeIf { it > 0 },
            )
        }
    }

    private companion object {
        const val SEARCH_URL = "https://api.discogs.com/database/search"
        const val ARTISTS_URL = "https://api.discogs.com/artists"
        const val RELEASES_URL = "https://api.discogs.com/releases"
        const val MASTERS_URL = "https://api.discogs.com/masters"
    }
}
