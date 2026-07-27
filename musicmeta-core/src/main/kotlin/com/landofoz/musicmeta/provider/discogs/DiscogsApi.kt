package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowAuth
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Discogs API client. Requires a personal access token.
 * Rate limited to 60 requests/minute (1000ms interval).
 */
internal class DiscogsApi(
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
            "&artist=$encodedArtist&per_page=$limit"
        val json = rateLimiter.execute { fetch(url) } ?: return emptyList()
        return parseReleaseResults(json)
    }

    /**
     * Finds the Discogs ID of the artist a human would mean by [name].
     *
     * A search API's hit 0 is a ranking, not an answer (docs/pitfalls.md §7), and this call asked
     * for `per_page=1`, so the caller's name check saw one hit and never the pool. Fetch a pool,
     * keep the candidates [ArtistMatcher] accepts, and rank them by [ArtistMatcher.matchQuality].
     *
     * Two Discogs specifics shape this, both confirmed against live payloads:
     *
     * 1. The name field is **`title`**, not `name`, and it carries Discogs's `" (n)"` disambiguator.
     *    That suffix is **arbitrary** — the bare name goes to whoever was catalogued first, not to
     *    the better-known artist. Searching "Bad Company" returns `Bad Company (3)` (the Paul
     *    Rodgers rock band) at rank 0 and `Bad Company` (a UK drum & bass group) at rank 1, so
     *    ranking the bare name higher would pick the *wrong* artist. [stripDisambiguator] removes it
     *    before matching, which puts both at the same rank and lets Discogs's order settle it.
     * 2. **The payload carries no popularity signal** — an artist result is only `id`, `type`,
     *    `master_id`, `master_url`, `uri`, `title`, `thumb`, `cover_image`, `resource_url`. The
     *    have/want/rating counts exist on *release* results, not these. So there is no popularity
     *    tiebreak: equal-quality candidates fall back to Discogs's own order, since [maxWithOrNull]
     *    keeps the first maximum.
     *
     * Name quality is therefore the only ranking, never overridden by anything.
     */
    suspend fun searchArtist(name: String): Long? {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$SEARCH_URL?type=artist&q=$encoded&per_page=$ARTIST_SEARCH_LIMIT"
        val json = rateLimiter.execute { fetch(url) } ?: return null
        val results = json.optJSONArray("results") ?: return null
        val id = (0 until results.length())
            // A non-object element is skipped, not thrown: a JSONException here would surface as
            // Error and open the breaker against a healthy Discogs (docs/pitfalls.md §4).
            .mapNotNull { results.optJSONObject(it) }
            .filter { ArtistMatcher.isMatch(name, it.candidateName()) }
            .maxWithOrNull(compareBy { ArtistMatcher.matchQuality(name, it.candidateName()) })
            ?.optLong("id", 0L) ?: return null
        return if (id > 0) id else null
    }

    private fun JSONObject.candidateName(): String =
        stripDisambiguator(optString("title", ""))

    /** Fetch artist details including band members. */
    suspend fun getArtist(artistId: Long): DiscogsArtist? {
        val url = "$ARTISTS_URL/$artistId"
        val json = rateLimiter.execute { fetch(url) } ?: return null
        return parseArtist(json)
    }

    /** Fetch all versions of a master release (pressings, editions). */
    suspend fun getMasterVersions(masterId: Long): List<DiscogsMasterVersion> {
        val url = "$MASTERS_URL/$masterId/versions?per_page=100"
        val json = rateLimiter.execute { fetch(url) } ?: return emptyList()
        return parseMasterVersions(json)
    }

    /** Fetch release details including extraartists and tracklist. */
    suspend fun getReleaseDetails(releaseId: Long): DiscogsReleaseDetail? {
        val url = "$RELEASES_URL/$releaseId"
        val json = rateLimiter.execute { fetch(url) } ?: return null
        return parseReleaseDetail(json)
    }

    /** Discogs takes the token as a header; keeping it out of the URL keeps it out of access logs. */
    private suspend fun fetch(url: String): JSONObject? {
        val headers = mapOf("Authorization" to "Discogs token=${tokenProvider()}")
        return httpClient.fetchJsonResult(url, headers).bodyOrThrowAuth()
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

        /** Candidate pool size for artist search — enough hits for a wrong name to be passed over. */
        const val ARTIST_SEARCH_LIMIT = 10

        /** Discogs's trailing homonym counter: "Nirvana (2)", "Bad Company (3)". */
        private val DISAMBIGUATOR_REGEX = Regex("\\s*\\(\\d+\\)$")

        /**
         * Drops Discogs's `" (n)"` homonym counter so two artists sharing a name compare as equals.
         * Only a trailing all-digit group goes — "Bad Company (3)" becomes "Bad Company", while a
         * meaningful parenthetical like "Air (French Band)" is left alone.
         */
        fun stripDisambiguator(title: String): String =
            title.replace(DISAMBIGUATOR_REGEX, "").trim()
        const val ARTISTS_URL = "https://api.discogs.com/artists"
        const val RELEASES_URL = "https://api.discogs.com/releases"
        const val MASTERS_URL = "https://api.discogs.com/masters"
    }
}
